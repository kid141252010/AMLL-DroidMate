# AMLL DroidMate 前端歌词界面显示问题分析报告

## 技术栈概览

- **Android 端**: Kotlin + Jetpack Compose + WebView (`AMLLLyricsView.kt`)
- **前端渲染**: 原生 JS (非 React 组件)，使用 `@applemusic-like-lyrics/core@0.2.0` 的 `LyricPlayer` + `BackgroundRender`
- **构建**: Vite 7 IIFE 模式打包 → `amll.bundle.js` + `frontend.css` → 复制到 `app/src/main/assets/amll/`
- **通信**: Kotlin 通过 `evaluateJavascript()` 调用 `window.updateLyrics()` 等函数；JS 通过 `Android.log()` / `Android.onLineClick()` 回调

---

## 发现的关键问题

### 问题 1（致命）：`rebuildBackgroundRender()` 失败会导致整个播放器无法创建

**文件**: `frontend/src/main.jsx:641-646`

```js
try {
  ensureUnblurStyle()
  rebuildBackgroundRender()   // ← 如果这里抛异常...
  player = new LyricPlayer()  // ← 这行永远不会执行！
  // ... 所有后续设置都不会发生
} catch (error) {
  logToAndroid(`[AMLL-ERROR] Failed to create LyricPlayer: ${error?.message || error}`)
}
```

`rebuildBackgroundRender()` 调用 `BackgroundRender.new(PixiRenderer)`，需要 WebGL 支持。如果 Android WebView 的 WebGL 不可用或 PixiJS 初始化失败，异常被捕获但 `player` 保持 `null`。此后所有 `window.updateLyrics()`、`window.updateTime()` 等调用因为检查 `if (player)` 而全部变成空操作。**歌词完全不会显示。**

**修复方案**：将背景渲染和播放器创建分离，确保播放器即使没有背景也能创建：

```js
try {
  ensureUnblurStyle()
  try {
    rebuildBackgroundRender()
  } catch (bgError) {
    logToAndroid(`[AMLL-WARN] Background render failed (non-fatal): ${bgError?.message}`)
  }
  player = new LyricPlayer()
  // ... 后续设置
} catch (error) {
  logToAndroid(`[AMLL-ERROR] Failed to create LyricPlayer: ${error?.message || error}`)
}
```

---

### 问题 2（致命）：CSS `!important` 覆盖运行时布局计算

**文件**: `frontend/src/main.jsx:1096-1106`

动态注入的样式：

```css
.amll-lyric-player {
  padding-top: 0 !important;
  padding-bottom: 0 !important;
  height: 100vh !important;
  max-height: 100vh !important;
  overflow: hidden !important;
}
```

但 `calcLayout()` 覆盖（第 689-776 行）通过 **内联样式** 设置 padding 和 height：

```js
this.element.style.paddingTop = pad + 'px'                    // 被 !important 覆盖为 0
this.element.style.height = baseHeight + pad * 2 + 'px'       // 被 !important 覆盖为 100vh
```

CSS `!important` 优先级高于内联样式，导致：

- 顶部/底部留白空间被清零，歌词可能挤在一起
- 高度被强制锁定在 100vh，`overflow: hidden` 裁剪所有溢出内容
- `calcLayout` 中的 `parseFloat(this.element.style.height)` 尝试解析 `"100vh"` 会得到 NaN，回退到 `clientHeight`

**修复方案**：移除注入样式中的 `!important`，或改用 JS 直接操作 `style.setProperty('height', '100vh', 'important')` 等方式协调布局。

---

### 问题 3（严重）：`mix-blend-mode: plus-lighter !important` 在 Android WebView 中的渲染问题

**文件**: `app/src/main/assets/amll/index.html:48`

```css
#app .amll-lyric-player {
  mix-blend-mode: plus-lighter !important;
}
```

而 `applyPlayerStyle()` 设置 `element.style.mixBlendMode = 'normal'`。由于 CSS `!important` 优先级更高，实际生效的是 `plus-lighter`。

`plus-lighter` 混合模式在透明背景的 Android WebView 中可能产生渲染异常：

- WebView 设置了 `setBackgroundColor(Color.TRANSPARENT)` + `LAYER_TYPE_NONE`
- `plus-lighter` 需要与 backdrop 做颜色叠加，但透明 WebView 的合成行为因设备/系统版本而异
- **在部分设备上可能导致文字完全不可见或极度模糊**

**修复方案**：移除 `index.html` 中的 `mix-blend-mode: plus-lighter !important`，让 JS 设置的 `normal` 生效：

```css
#app .amll-lyric-player {
  /* mix-blend-mode: plus-lighter !important;  ← 删除此行 */
  mix-blend-mode: normal;
}
```

---

### 问题 4（中等）：Assets 中的 bundle 文件与最新构建不一致

```
frontend/dist/amll.bundle.js  → 415,950 bytes
assets/amll/amll.bundle.js    → 416,716 bytes  (差异 766 bytes)
```

assets 目录中的 bundle 不是从最新 `dist/` 复制的，可能包含旧版本代码中的 bug。

**修复方案**：重新执行 `npm run build:android`，确保 assets 中的文件与最新构建一致。

---

### 问题 5（中等）：`buildLyricsJson` 中 fallback 路径的双重转义

**文件**: `AMLLLyricsView.kt:427-440`

```kotlin
val text = line.text.replace("\\", "\\\\").replace("\"", "\\\"")  // 第一次转义
// ...
val wordText = text.replace("\"", "\\\"")  // 第二次转义！
```

当 `line.words` 为空时，使用已经转义过的 `text` 再次转义，导致双重转义。例如原始文本 `He said "hi"` 变成 `He said \\"hi\\"` 而不是 `He said \"hi\"`，造成 JSON 解析错误。

**修复方案**：使用原始 `line.text` 进行 word fallback 的转义：

```kotlin
val wordText = line.text.replace("\\", "\\\\").replace("\"", "\\\"")
```

---

### 问题 6（低）：React 导入完全无用

**文件**: `frontend/src/main.jsx:1-2`

```js
import React, { useState } from 'react'
import ReactDOM from 'react-dom'
```

整个文件使用原生 DOM API，React/ReactDOM/useState 从未被使用。这白白增加了约 200KB 的 bundle 体积，并且 React 的初始化代码可能产生副作用。

**修复方案**：移除 React 相关导入，同时从 `package.json` 中移除 `react`、`react-dom`、`@vitejs/plugin-react`、`@applemusic-like-lyrics/react`。

---

### 问题 7（低）：触摸定位使用硬编码的过时 CSS 类名

**文件**: `frontend/src/main.jsx:461`

```js
let lyricLine = element.closest('._lyricLine_1vq69_6, ._lyricLine_1ygrf_6')
```

但 `frontend.css` 中实际的类名是 `_lyricLine_ut4sn_6` 和 `_lyricLine_1jop6_6`。这些哈希化的类名不匹配，导致精确的 CSS selector 匹配失败。虽然后面有 `[class*="lyric"]` 的兜底，但匹配不精确。

---

## 总结：推荐修复优先级

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **P0** | BackgroundRender 崩溃导致 player 不创建 | 歌词完全不显示 |
| **P0** | CSS `!important` 覆盖布局计算 | 歌词布局异常/不可见 |
| **P1** | `mix-blend-mode: plus-lighter` 在 WebView 中 | 部分设备文字不可见 |
| **P1** | Assets bundle 不一致 | 运行旧版有 bug 的代码 |
| **P2** | JSON 双重转义 | 无逐词数据时歌词显示乱码 |
| **P3** | 无用的 React 导入 | 包体积膨胀 |
| **P3** | 硬编码过时 CSS 类名 | 触摸交互不精确 |
