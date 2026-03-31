# TTML 乐段 (Song Part) 识别与显示 Implementation Plan

## 1. 目标

在保持现有歌词渲染链路稳定的前提下，新增 TTML `<div>` 乐段解析与主界面乐段导航条：

1. 识别 `<div>` 上的 `itunes:songPart` / `itunes:song-Part` / `songPart` / `song-Part`
2. 在主界面「歌词卡片」与「NowPlayingCard」之间显示乐段条
3. 点击乐段可跳转播放进度
4. 乐段名称保持原文，不做翻译
5. 无乐段时不占位，歌词区域保持现有高度策略
6. 全屏歌词模式同样显示乐段条（位于底部控件上方）

---

## 2. 现状与约束

### 2.1 当前现状

- `TTMLParser.parse(content)` 仅返回 `List<LyricLine>`，未返回乐段信息
- `UnifiedLyricsParser.parse()` 将 TTML 解析结果组装为 `TTMLLyrics(metadata, lines)`
- `MainScreen` 仅展示歌词区域与 `NowPlayingCard`，无乐段导航 UI

### 2.2 关键约束

- 乐段条位置是硬约束：必须在歌词区域与播放控件之间，不放在 WebView 内
- 现有 `TTMLParser.parse()` 在多处被调用，直接改返回类型会扩大改动面
- 现有 `TTMLLyrics` 为序列化数据模型，新增字段必须提供默认值以避免兼容性风险

---

## 3. 方案选择

### 最终方案

采用「后端解析 + Compose 原生渲染」，并保留兼容 API：

- 解析层新增详细结果接口，保留原 `parse()` 兼容方法
- UI 层新增 `SongPartsStrip` 组件，在 Compose 中渲染和交互

### 选择理由

1. 满足“位于歌词与播放控件之间”的布局要求
2. 避免引入 WebView bridge 改造
3. 通过兼容 API 降低对现有调用点与测试的冲击

---

## 4. 产品决策（已收敛）

1. 重复乐段名不合并，不编号；按时间顺序逐段展示（`Verse` 可出现多次）
2. 全屏模式显示乐段条，放在底部控制区上方
3. 当前乐段高亮采用 Chip/Pill 视觉差异（背景 + 文字色），样式跟随 `NowPlayingCard` 主色系
4. 无乐段数据时完全隐藏乐段条，并释放空间给歌词区

---

## 5. 技术设计

### 5.1 数据模型

**文件：** `app/src/main/java/com/amll/droidmate/domain/model/Lyrics.kt`

新增：

```kotlin
@Serializable
data class SongPart(
    val name: String,
    val startTime: Long,
    val endTime: Long
)
```

修改：

```kotlin
@Serializable
data class TTMLLyrics(
    val metadata: TTMLMetadata,
    val lines: List<LyricLine>,
    val songParts: List<SongPart> = emptyList()
)
```

### 5.2 TTML 解析

**文件：** `app/src/main/java/com/amll/droidmate/data/parser/TTMLParser.kt`

新增结果类型与兼容 API：

```kotlin
data class TTMLParseResult(
    val lines: List<LyricLine>,
    val songParts: List<SongPart>
)

fun parseWithSongParts(content: String): TTMLParseResult
fun parse(content: String): List<LyricLine> = parseWithSongParts(content).lines
```

解析规则：

1. 遍历 `<body>` 直属子节点，优先按文档顺序处理 `<div>`
2. 对每个 `<div>` 读取 songPart 属性（四种 key 兼容）
3. `<div>` 时间取值策略：
   - 优先使用 `<div begin/end>`
   - 缺失时回退为该 `<div>` 下首尾歌词行时间
4. 仅当 `name` 非空且 `endTime > startTime` 时写入 `SongPart`
5. 歌词行解析继续复用现有 `parseParagraph()` 逻辑，确保行为不回归

### 5.3 统一解析器透传

**文件：** `app/src/main/java/com/amll/droidmate/data/parser/UnifiedLyricsParser.kt`

- TTML 分支改用 `TTMLParser.parseWithSongParts()`
- 构建 `TTMLLyrics` 时透传 `songParts`
- `parseWithFormat(..., TTML)` 仍返回歌词行列表（走兼容 `parse()`）

### 5.4 Repository 透传

**文件：** `app/src/main/java/com/amll/droidmate/data/repository/LyricsRepository.kt`

- `parseTTML(...)` 改用 `parseWithSongParts()`
- 构造 `TTMLLyrics` 时赋值 `songParts`

### 5.5 主界面 UI

**文件：** `app/src/main/java/com/amll/droidmate/ui/screens/MainScreen.kt`

新增 `SongPartsStrip` 组件：

- 输入：`songParts`、`currentPosition`、`onSeekToPart`
- 展示：水平滚动 Chip/Pill
- 交互：点击 `viewModel.seekTo(songPart.startTime)`
- 高亮：`currentPosition in [startTime, endTime)`

布局落点：

1. 非全屏：歌词 Card 下方、`NowPlayingCard` 上方
2. 全屏：底部控件条上方
3. 使用 `AnimatedVisibility` 控制显隐，空列表不占位

---

## 6. 分阶段实施清单

### Phase 1：模型与解析（必做）

- [ ] 在 `Lyrics.kt` 新增 `SongPart` 并扩展 `TTMLLyrics.songParts`
- [ ] 在 `TTMLParser.kt` 新增 `TTMLParseResult` 与 `parseWithSongParts`
- [ ] 保留 `parse(content): List<LyricLine>` 兼容入口

### Phase 2：调用链透传（必做）

- [ ] `UnifiedLyricsParser.kt` TTML 分支透传 `songParts`
- [ ] `LyricsRepository.kt` 的 `parseTTML` 透传 `songParts`

### Phase 3：UI 与交互（必做）

- [ ] 在 `MainScreen.kt` 新增 `SongPartsStrip`
- [ ] 接入非全屏布局
- [ ] 接入全屏布局
- [ ] 完成当前乐段高亮与点击跳转

### Phase 4：测试与验收（必做）

- [ ] 新增/更新解析测试覆盖 songPart 读取与兼容属性名
- [ ] 回归现有 TTML 空格保留行为
- [ ] 完成手动验收路径

---

## 7. 受影响文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `app/src/main/java/com/amll/droidmate/domain/model/Lyrics.kt` | MODIFY | 新增 `SongPart`，扩展 `TTMLLyrics.songParts` |
| `app/src/main/java/com/amll/droidmate/data/parser/TTMLParser.kt` | MODIFY | 新增 `parseWithSongParts` 与 songPart 解析逻辑 |
| `app/src/main/java/com/amll/droidmate/data/parser/UnifiedLyricsParser.kt` | MODIFY | TTML 分支透传 `songParts` |
| `app/src/main/java/com/amll/droidmate/data/repository/LyricsRepository.kt` | MODIFY | `parseTTML` 透传 `songParts` |
| `app/src/main/java/com/amll/droidmate/ui/screens/MainScreen.kt` | MODIFY | 新增并接入 `SongPartsStrip` |
| `app/src/test/java/com/amll/droidmate/data/parser/TTMLWhitespacePreserveTest.kt` | MODIFY | 适配/回归 TTMLParser 兼容入口 |
| `app/src/test/java/com/amll/droidmate/data/parser/*` | ADD/MODIFY | 新增 songPart 解析专项测试 |

---

## 8. 风险与缓解

1. **风险：`<div>` 无 begin/end 导致时间非法**  
   缓解：回退到子歌词行时间；非法区间丢弃

2. **风险：修改解析入口影响老调用点**  
   缓解：保留 `parse(content): List<LyricLine>` 兼容方法

3. **风险：全屏布局拥挤**  
   缓解：限制乐段条高度、超出横向滚动，不挤压控件点击区域

4. **风险：无 songPart 时 UI 抖动**  
   缓解：`AnimatedVisibility` + 固定 enter/exit 动画时长

---

## 9. 验证计划

### 9.1 自动化

1. 单元测试：
   - `TTMLParser` 读取四种 songPart 属性名
   - `TTMLParser` 在无 songPart/无 div 时间时行为正确
   - `UnifiedLyricsParser` TTML 分支可得到 `songParts`
2. 运行测试：

```powershell
.\gradlew.bat test
```

3. 发行版构建验证：

```powershell
.\gradlew.bat :app:assembleRelease
```

### 9.2 手工验证

1. 播放含 songPart 的 TTML：乐段条出现，名称与顺序正确
2. 播放不含 songPart 的 TTML/LRC：乐段条不出现
3. 点击任一乐段：进度跳转到对应起始时间
4. 播放推进：当前乐段高亮随时间切换
5. 进入全屏：乐段条在底部控件上方可见且可点击

---

## 10. 完成定义（Definition of Done）

满足以下条件即视为完成：

1. 代码实现与本计划一致，主流程无编译错误
2. 自动化测试通过，且 `:app:assembleRelease` 成功
3. 非全屏与全屏两种模式下乐段条表现符合决策
4. 无 songPart 的歌词展示不发生回归
