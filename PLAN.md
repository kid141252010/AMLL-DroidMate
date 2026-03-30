## 方案：先消灭“横跳”，再清理歌词显示链路中的另外 3 个重大问题

### 摘要
当前“高亮歌词在顶部与底部反复横跳”不是单点 bug，而是 3 套定位机制同时生效造成的竞争：

- 宿主层在 [main.jsx](E:/AMLL-DroidMate/frontend/src/main.jsx):696 重写了 `calcLayout()`，会二次修改 `scrollOffset` 并再次跑布局。
- 核心库自己的 DOM 播放器在 `calcLayout()` 内会执行 `scrollIntoView()` / `element.scrollBy()`，属于另一套自动滚动逻辑。
- 时间更新同时来自 Kotlin `updateTime()` 推送和 JS `requestAnimationFrame` 循环，导致同一帧附近重复 `setCurrentTime()/update()`。

推荐的修复方向是：**只保留一个滚动/布局 owner，并让 JS RAF 成为唯一渲染驱动**。这一版不升级依赖，不换库，只把宿主层的“抢控制权”逻辑撤掉。

### 关键修改
- 在 [main.jsx](E:/AMLL-DroidMate/frontend/src/main.jsx) 删除对 `player.setLyricLines` 的 monkey-patch。
  - 不再写入 `bufferedLines`、`scrollToIndex = 0`、`calcLayout(true)`。
  - 原因：`bufferedLines` 在上游不是“纯缓存”，它会直接参与 `scrollToIndex` 和激活窗口计算；把全部行塞进去会污染对齐锚点和高亮窗口。
- 在 [main.jsx](E:/AMLL-DroidMate/frontend/src/main.jsx) 删除对 `player.calcLayout` 的整段重写。
  - 不再手动修改 `scrollOffset`。
  - 不再改 `paddingTop/paddingBottom/height`。
  - 不再调用 `bottomLine.setTransform()`。
  - 不再覆盖 `limitScrollOffset()`。
- 回退宿主层的“页面流滚动实验”，让播放器只走核心库自己的布局模型。
  - 去掉 `document.documentElement/body overflowY = 'auto'`。
  - 去掉 `playerElement.style.position = 'absolute'`、`inset = '0'`、`minHeight = '100vh'`。
  - 在 `applyPlayerStyle()` 里不要再强制 `height = 'auto'`，让核心 CSS 的高度模型接管。
- 收敛时间驱动，只保留 JS RAF 真正驱动播放器。
  - `window.updateTime()` 只更新 `state.currentTime` 和 seeking 状态，不再直接 `callPlayer('setCurrentTime')` / `callPlayer('update')`。
  - `animationFrameLoop()` 继续作为唯一的 `setCurrentTime + update` 调用方。
  - 在 [AMLLLyricsView.kt](E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt) 增加 `lastSentCurrentTime` 缓存，避免把相同时间重复 `evaluateJavascript(updateTime)` 发给 WebView。
- 顺手修掉另外两个歌词显示重大问题：
  - 删除生产环境里的假歌词 fallback：`updateLyrics()` 收到空歌词时不要再注入 `Demo`，否则会掩盖真实“无歌词/解析失败”问题。
  - 保留背景渲染失败非致命逻辑和 `mixBlendMode = normal` 的 WebView 兼容策略，不在这次回退。

### 继续探究的其它重大问题
本轮实现后要继续验证并记录这 3 类问题是否随之消失；如果未消失，再单独开第二轮：

- 私有内部状态被宿主层篡改。
  - 证据：`bufferedLines` / `scrollToIndex` / `scrollOffset` / `limitScrollOffset` 都被宿主层直接改写。
  - 预期：本轮移除后，高亮窗口应恢复成核心库原生行为。
- 双时间驱动导致布局抖动。
  - 证据：JS RAF 在 [main.jsx](E:/AMLL-DroidMate/frontend/src/main.jsx):588 每帧调用 `setCurrentTime/update`，而 `window.updateTime()` 在 [main.jsx](E:/AMLL-DroidMate/frontend/src/main.jsx):952 也会立即再调一次，Kotlin 又在 [AMLLLyricsView.kt](E:/AMLL-DroidMate/app/src/main/java/com/amll/droidmate/ui/screens/AMLLLyricsView.kt):212 持续推送。
  - 预期：收敛后，同一时间点只会有一条渲染链路。
- 空歌词被假数据掩盖。
  - 证据：`updateLyrics()` 空数组时注入 `Demo`。
  - 预期：空歌词应显示为空态或保持空白，不应出现伪造歌词。

### 测试方案
- 稳定播放测试：
  - 连续播放 30 秒密集逐词歌词，高亮行只能单向过渡，不能在同一时间段上下反复切换。
- 跳播测试：
  - 连续前后 seek 10 次，高亮行应在 2 帧内落到目标附近，不允许先跳到顶部/底部再回弹。
- 特殊歌词测试：
  - 带 `isBG` 背景歌词、对唱歌词、长间奏、无逐词时间轴歌词都要验证一次。
- 空歌词测试：
  - 传入 `lines=[]` 时不出现 `Demo`，也不报 JS 运行时异常。
- 回归测试：
  - 行点击 seek、暂停/恢复、字体切换、背景图切换后，高亮定位仍稳定。
- 诊断输出：
  - 临时增加节流日志，记录 `scrollToIndex`、`targetAlignIndex`、`calcLayout` 调用次数、`updateTime` 与 RAF 的调用频率；验收时确认没有双倍触发。

### 默认假设
- 这一轮优先稳定性，不保留当前“页面自然滚动”实验。
- 这一轮不升级 `@applemusic-like-lyrics/core`，只清理宿主层补丁。
- 这一轮不改 Android 侧交互协议，只做前端布局与时间驱动收敛，以及 Kotlin 侧去重发送。
