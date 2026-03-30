# AMLL 歌词延迟与性能修复方案（用于覆盖 [PLAN.md](/E:/AMLL-DroidMate/PLAN.md)）

## Summary
当前“歌词间歇性延迟 + 性能不佳”主要不是单点 bug，而是 4 条链路叠加：

- 时间源过粗且不连续：`MediaInfoService` 只按 `500ms` 轮询一次播放位置并直接写入 `NowPlayingMusic.currentPosition`，[MediaInfoService.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/service/MediaInfoService.kt#L58) 到 [MediaInfoService.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/service/MediaInfoService.kt#L109)；`MainViewModel` 也只是原样取这个值加 offset，[MainViewModel.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/viewmodel/MainViewModel.kt#L151) 到 [MainViewModel.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/viewmodel/MainViewModel.kt#L159)。JS 的 RAF 只是反复消费旧时间，所以高亮会天然出现 0 到 500ms 的阶梯式滞后。
- 每帧跨 WebView bridge 取播放态：JS 动画循环里每一帧都调用 `Android.isPlaying()`，[main.jsx](/E:/AMLL-DroidMate/frontend/src/main.jsx#L599) 到 [main.jsx](/E:/AMLL-DroidMate/frontend/src/main.jsx#L607)。这会产生持续的 JS<->Java 同步桥开销，是稳定的掉帧源。
- 每次进度更新都重复做配置/文件工作：`AndroidView.update` 在每次 `currentTime` 变化时都会重新读 SharedPreferences、解析字体 JSON、`File.exists()`、重新拼接配置字符串，[AMLLLyricsView.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt#L229) 到 [AMLLLyricsView.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt#L327)；对应的设置读取本身包含 JSON 解析，[AppSettings.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/AppSettings.kt#L133) 到 [AppSettings.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/AppSettings.kt#L166)、[AppSettings.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/AppSettings.kt#L429) 到 [AppSettings.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/AppSettings.kt#L475)。
- WebView 生命周期过重：歌词层只有在 `lyrics != null` 时才创建，[MainScreen.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/MainScreen.kt#L616) 到 [MainScreen.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/MainScreen.kt#L630)；释放时还会 `clearCache(true)`，[AMLLLyricsView.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt#L330) 到 [AMLLLyricsView.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt#L337)。这会让“歌词刚出现时”的首帧显示延迟更加明显。

## Public Interfaces
- 扩展 `NowPlayingMusic`：新增 `playbackSpeed: Float`、`positionAnchorMs: Long`、`positionAnchorElapsedMs: Long`，保留 `currentPosition` 作为兼容字段但不再作为 AMLL 主时间源。[Lyrics.kt](/E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/domain/model/Lyrics.kt#L10)
- `AMLLLyricsView` 改为接收播放时间轴快照，而不是裸 `currentTime: Long`；`isPlaying` 保留，但只用于同步状态，不再让 JS 主动轮询。
- 前端桥新增 `window.syncPlayback(payload)`；`window.updateTime()` 保留为兼容包装，内部转调 `syncPlayback`，逐步废弃直接 time-only 推送。

## Key Changes
- 重建播放时间轴。
  - `MediaInfoService` 为当前 `MediaController` 注册 `MediaController.Callback`，在 `onPlaybackStateChanged` / `onMetadataChanged` 时立即推送快照。
  - 保留轮询，但只用于发现 session 切换和兜底，轮询间隔放宽到 `1000-2000ms`，不再承担高精度进度刷新。
  - 生成快照时使用 `playbackState.position + (elapsedRealtime - lastPositionUpdateTime) * playbackSpeed` 作为锚点信息，不再只存采样瞬间的 position。
- 把连续时间估算移到 WebView 内部。
  - JS 维护 `anchorPositionMs + (performance.now() - anchorPerfNow) * speed` 的连续时钟；seek、pause、resume、新歌切换时重置锚点。
  - 删除 RAF 内的 `Android.isPlaying()` 调用，改为 Kotlin 在播放态变化时调用 `window.setPaused()` 或通过 `syncPlayback` 一并下发。
  - `animationFrameLoop` 继续做 `setCurrentTime/update`，但使用本地连续时间，不再等待桥每 500ms 喂一次。
- 收敛 `AndroidView.update` 的热路径。
  - 拆成两类同步：高频播放态同步、低频配置同步。
  - 字体、动画、背景配置改为在 `renderMode`、页面 ready、设置页返回/`ON_RESUME`、歌词对象变化时才重算；不再跟随 `currentTime` 每次重跑。
  - `lyric offset` 查表结果缓存到当前歌曲/设备/来源维度；不要在每次 `MainScreen` 重组时重新解析 JSON。
- 降低 WebView 冷启动成本。
  - `LyricsVisualLayer` 始终保留 AMLL WebView；无歌词时发送空 payload 或 clear 命令，而不是卸载组件。
  - `onRelease` 删除 `clearCache(true)`；只做 `stopLoading/removeJavascriptInterface/destroy`。
  - 保留 `lastLyricsPayload` 的热重放逻辑，但页面正常情况下不再因为 `lyrics == null` 被销毁。
- 做默认渲染降载。
  - 嵌入态背景效果默认降到 `30fps`、更低 `renderScale`，暂停或无歌词时切到 `staticMode=true`。
  - 全屏态可提升到 `45fps`；不默认追求 `60fps` 背景流动。
- 前端产物同步。
  - 前端修改后统一通过 `frontend` 的 `build:android` 刷新 `app/src/main/assets/amll`，避免源码和打包产物漂移。

## Test Plan
- 连续播放 60 秒逐字歌词：高亮切换误差应稳定收敛到 `<80ms`，不再出现 0 到 500ms 的阶梯延迟。
- 暂停/恢复：暂停后高亮停住；恢复后从正确时间继续，不允许先落回旧行再追上。
- 快速 seek 10 次：每次都应在 2 帧内跳到目标附近，不出现“先回旧位置再纠正”。
- 切歌场景：缓存命中、网络命中、无歌词三种情况都不能再因 WebView 重建出现明显白屏或首帧迟到。
- 设置回归：字体切换、动画开关、背景参数切换只触发一次配置同步，不随播放时间持续抖动。
- 性能验收：记录 `evaluateJavascript` 次数、JS bridge 调用次数、主线程帧耗时；目标是去掉 RAF 内桥调用后，bridge 调用量降到“状态变化级”，而不是“每帧级”。

## Assumptions
- 这一轮优先“歌词同步稳定 + 主线程/bridge 降载”，不追求保留当前背景动画的最高视觉强度。
- 不升级 `@applemusic-like-lyrics/core`，先修宿主层时间轴、桥接和生命周期设计。
- 最终交付时，直接用本方案内容覆盖 [PLAN.md](/E:/AMLL-DroidMate/PLAN.md)。
