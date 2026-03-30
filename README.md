# AMLL DroidMate

即开即用 Android 端外置歌词显示器，集成 [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) 风格渲染与多源歌词检索能力。

<img src="https://github.com/user-attachments/assets/3d34c9d8-37d5-4ca6-b45b-be02bc85e377" width="500">



## 核心特性

- 在你爱用的音乐源应用上享受 [AMLL](https://github.com/amll-dev/applemusic-like-lyrics) 的功能，精彩体验就在口袋之中。
  - 享受类似于 Apple Music 的动画效果，包括长音辉光、背景渐变等
    - 可选：基于修改 font-family 的自定义字体功能
  - [TTML](https://github.com/amll-dev/amll-ttml-db/blob/main/instructions/ttml-specification.md) 歌词特性：（仅限TTML格式）
    - 多角色歌词将不同角色的歌词分成靠左和靠右
    - 背景歌词将在主歌词下以小字显示
    - 支持行与行之间的时间轴重叠
  - 无需切换应用和提前下载解密。继续使用且不影响您的音乐会员和喜爱的歌单。
  - 应用内可直接拖动进度条，暂停/播放，上下首等，而无需回到音乐源
    - 可选功能：按“上一首”时回到0:00处
- 多源歌词检索（[AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db)、酷狗、网易云、QQ），找到最符合且功能最多的歌词文件
  - 实验性：为非原生TTML歌词处理元数据和角色识别 
- 常驻通知实时歌词：可选常驻通知实时显示当前句歌词，并支持锁屏显示
- 应用全局颜色根据专辑图变换
- 基于歌曲和输出设备的歌词偏移，轻松解决延迟问题

## 技术栈

- Android 客户端：Kotlin、Jetpack Compose、Material3、AndroidX（含 Media3 / Lifecycle / WebKit）
- 前端歌词渲染：React + Vite，集成 [@applemusic-like-lyrics/core](https://www.npmjs.com/package/@applemusic-like-lyrics/core) 与 `@applemusic-like-lyrics/react`
- 网络与数据层：Ktor、Kotlinx Serialization、Coroutines、Room、Timber
- 多源歌词能力：AMLL TTML DB、QQ 音乐、网易云、酷狗（统一检索、解析与特性匹配）
- 构建与工程：Gradle Kotlin DSL（Android）+ npm（frontend）

## 参考和接入的项目

- 核心动效：[Apple Music-like Lyrics （AMLL） `amll-dev/applemusic-like-lyrics`](https://github.com/amll-dev/applemusic-like-lyrics)
- 多源歌词匹配：[Unilyric `apoint123/Unilyric`](https://github.com/apoint123/Unilyric)
- 优质歌词来源：[AMLL TTML DB `amll-dev/amll-ttml-db`](https://github.com/amll-dev/amll-ttml-db)

