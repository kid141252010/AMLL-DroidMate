package com.amll.droidmate.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.amll.droidmate.data.parser.escapeXML
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amll.droidmate.ui.AppSettings
import com.amll.droidmate.websocket.AMLLWebSocketClient
import androidx.compose.ui.viewinterop.AndroidView
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.websocket.WsProtocolV2Helper
import timber.log.Timber
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对齐原AMLL项目的两种DOM渲染策略:
 * DOM: 使用AMLL Core的LyricPlayer
 * DOM_LITE: 使用轻量DOM渲染(阉割版)
 */
enum class AMLLRenderMode {
    DOM,
    DOM_LITE
}

private const val AMLL_LOG_TAG = "AMLL"
private val AMLL_VIEW_INSTANCE_COUNTER = AtomicInteger(0)

@Composable
fun AMLLLyricsView(
    lyrics: TTMLLyrics?,
    currentTime: Long,
    musicId: String = "",
    musicName: String = "Unknown",
    albumName: String = "",
    artistName: String = "Unknown",
    duration: Long = 0L,
    albumArtUri: String? = null,
    renderMode: AMLLRenderMode = AMLLRenderMode.DOM,
    debugSource: String = "unknown",
    onLyricsClick: (() -> Unit)? = null,
    onLineSeek: ((Long) -> Unit)? = null,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webViewEnabled = AppSettings.isWebViewEnabled(context)
    
    // WebSocket 客户端和状态
    val webSocketClient = remember { 
        com.amll.droidmate.websocket.AMLLWebSocketClient.getInstance() 
    }
    var isWebSocketConnected by remember { mutableStateOf(false) }
    
    // 初始化 WebSocket 监听器（无论 WebView 是否启用都执行）
    // 这样即使禁用 WebView，也能通过 WebSocket 同步播放状态
    InitializeWebSocketListener(
        musicId = musicId,
        musicName = musicName,
        albumName = albumName,
        artistName = artistName,
        duration = duration,
        currentTime = currentTime,
        isPlaying = isPlaying,
        lyrics = lyrics,
        debugSource = debugSource,
        onCommandReceived = { command, valueObj ->
            when (command) {
                "pause" -> {
                    Timber.i("[AMLLLyrics] 收到暂停命令，执行暂停操作")
                    // 发送系统广播：媒体按钮事件（暂停）
                    val pauseIntent = android.content.Intent("android.intent.action.MEDIA_BUTTON").apply {
                        putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                        addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    }
                    context.sendBroadcast(pauseIntent)
                }
                "resume" -> {
                    Timber.i("[AMLLLyrics] 收到恢复播放命令，执行播放操作")
                    // 发送系统广播：媒体按钮事件（播放）
                    val playIntent = android.content.Intent("android.intent.action.MEDIA_BUTTON").apply {
                        putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                        addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    }
                    context.sendBroadcast(playIntent)
                }
                "forwardSong" -> {
                    Timber.i("[AMLLLyrics] 收到下一首命令，执行下一首操作")
                    // 发送系统广播：媒体按钮事件（下一首）
                    val nextIntent = android.content.Intent("android.intent.action.MEDIA_BUTTON").apply {
                        putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                        addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    }
                    context.sendBroadcast(nextIntent)
                }
                "backwardSong" -> {
                    Timber.i("[AMLLLyrics] 收到上一首命令，执行上一首操作")
                    // 发送系统广播：媒体按钮事件（上一首）
                    val prevIntent = android.content.Intent("android.intent.action.MEDIA_BUTTON").apply {
                        putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                        addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                    }
                    context.sendBroadcast(prevIntent)
                }
                "seekPlayProgress" -> {
                    val progress = valueObj?.get("progress")?.jsonPrimitive?.content?.toLongOrNull()
                    if (progress != null) {
                        Timber.i("[AMLLLyrics] 收到跳转进度命令：$progress ms，执行跳转操作")
                        // 使用 MediaInfoService 进行跳转
                        val mediaInfoService = com.amll.droidmate.service.MediaInfoService(context)
                        mediaInfoService.seekTo(progress)
                    } else {
                        Timber.w("[AMLLLyrics] 跳转进度命令参数无效")
                    }
                }
                "setVolume" -> {
                    val volume = valueObj?.get("volume")?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (volume != null) {
                        Timber.i("[AMLLLyrics] 收到音量设置命令：$volume")
                        // 使用 AudioManager 设置系统音量
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        // 将 0.0-1.0 的音量转换为系统音量级别（0-15）
                        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val targetVolume = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            targetVolume,
                            0  // 不显示音量 UI
                        )
                        Timber.d("[AMLLLyrics] 音量已设置：${volume} -> $targetVolume/$maxVolume")
                    } else {
                        Timber.w("[AMLLLyrics] 音量设置命令参数无效")
                    }
                }
                "setRepeatMode", "setShuffleMode" -> {
                    Timber.d("[AMLLLyrics] 收到不支持的命令：$command，忽略")
                    // 忽略这些命令，不回复错误（避免频繁发送错误消息）
                }
                else -> {
                    Timber.d("[AMLLLyrics] 未知命令：$command")
                }
            }
        },
        onConnectedCallback = {
            isWebSocketConnected = true
            Timber.d("[AMLLLyrics] WebSocket 已连接，当前歌曲信息：musicId=$musicId, musicName=$musicName, artist=$artistName")
        },
        onErrorCallback = { error ->
            isWebSocketConnected = false
            // 打印更详细的错误信息
            when (error) {
                is java.io.EOFException -> {
                    Timber.e("[AMLLLyrics] 服务器主动断开了连接")
                }
                is java.net.ConnectException -> {
                    Timber.e("[AMLLLyrics] 无法连接到服务器")
                }
                else -> {
                    Timber.e("[AMLLLyrics] 未知错误类型：${error.javaClass.simpleName}")
                }
            }
        }
    )
    
    // 如果 WebView 被禁用，不渲染歌词 UI，但仍保持 WebSocket 通信
    if (!webViewEnabled) {
        Timber.d("[AMLLLyrics] [WebView] [$debugSource] WebView 已禁用，跳过歌词渲染（但 WebSocket 仍在运行）")
        return
    }
    
    val instanceId = remember { AMLL_VIEW_INSTANCE_COUNTER.incrementAndGet() }
    val onLyricsClickState = rememberUpdatedState(onLyricsClick)
    val onLineSeekState = rememberUpdatedState(onLineSeek)
    val isPlayingState = rememberUpdatedState(isPlaying)
    var isPageReady by remember { mutableStateOf(false) }
    var isBridgeReady by remember { mutableStateOf(false) }
    var lastModeValue by remember { mutableStateOf<String?>(null) }
    var lastBackgroundProfileValue by remember { mutableStateOf<String?>(null) }
    var lastLyrics by remember { mutableStateOf<TTMLLyrics?>(null) }
    var lastLyricsPayload by remember { mutableStateOf<String?>(null) }
    var lastAlbumArtUri by remember { mutableStateOf<String?>(null) }
    var lastFontConfigSignature by remember { mutableStateOf<String?>(null) }
    var lastMotionConfigValue by remember { mutableStateOf<String?>(null) }
    
    // 记录上次发送的状态，用于去重
    var lastSentMusicId by remember { mutableStateOf<String?>(null) }
    var lastSentMusicName by remember { mutableStateOf<String?>(null) }
    var lastSentAlbumName by remember { mutableStateOf<String?>(null) }
    var lastSentArtistName by remember { mutableStateOf<String?>(null) }
    var lastSentIsPlaying by remember { mutableStateOf<Boolean?>(null) }
    
    // 发送播放状态到 WebSocket 服务器（V2 JSON 协议）
    // 仅在歌曲信息或播放状态变化时发送，播放进度惯性除外
    fun sendPlaybackStatusToWebSocket(currentTime: Long, isPlaying: Boolean) {
        if (!isWebSocketConnected) {
            Timber.d("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] WebSocket 未连接，跳过发送")
            return
        }
            
        // 检查是否有状态变化
        val musicInfoChanged = musicId != lastSentMusicId ||
                               musicName != lastSentMusicName ||
                               albumName != lastSentAlbumName ||
                               artistName != lastSentArtistName
        
        val playingStateChanged = isPlaying != lastSentIsPlaying
        
        // 只有状态变化时才发送
        if (!musicInfoChanged && !playingStateChanged) {
            // 状态无变化，不发送（播放进度惯性除外）
            return
        }
            
        // V2 协议：使用 JSON 格式发送状态更新
        try {
            // 如果歌曲信息变化，发送新的歌曲信息
            if (musicInfoChanged) {
                val message = com.amll.droidmate.websocket.WsProtocolV2Helper.createSetMusicUpdate(
                    musicId = musicId,
                    musicName = musicName,
                    albumName = albumName,
                    artists = listOf(com.amll.droidmate.websocket.WsProtocolV2Helper.Artist("1", artistName)),
                    duration = duration
                )
                Timber.d("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] 发送歌曲信息 (V2 JSON): $musicName")
                webSocketClient.send(message)
                
                // 更新记录的状态
                lastSentMusicId = musicId
                lastSentMusicName = musicName
                lastSentAlbumName = albumName
                lastSentArtistName = artistName
            }
            
            // 如果播放状态变化，发送播放/暂停状态
            if (playingStateChanged) {
                val stateMessage = if (isPlaying) {
                    com.amll.droidmate.websocket.WsProtocolV2Helper.createResumedUpdate()
                } else {
                    com.amll.droidmate.websocket.WsProtocolV2Helper.createPausedUpdate()
                }
                
                Timber.d("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] 发送播放状态消息 (V2 JSON): isPlaying=$isPlaying")
                webSocketClient.send(stateMessage)
                
                // 更新记录的状态
                lastSentIsPlaying = isPlaying
            }
            
            // 注意：不发送播放进度更新（currentTime），避免频繁网络请求
        } catch (e: Exception) {
            Timber.e("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] 发送 V2 消息失败", e)
        }
    }
    
    // WebSocket 状态同步逻辑 - 当歌曲信息或播放状态变化时立即发送
    LaunchedEffect(musicId, musicName, albumName, artistName, duration, isPlaying) {
        if (AppSettings.isWebSocketProtocolEnabled(context) && isWebSocketConnected) {
            // 当歌曲信息或播放状态变化时，立即发送新状态（播放进度惯性除外）
            sendPlaybackStatusToWebSocket(currentTime, isPlaying)
        }
    }
    
    // 注入 WebSocket 桥接代码到 WebView
    // 使用统一的 TTMLConverter.toTTMLString() 代替本地实现
    

    
    fun injectWebSocketBridge(view: WebView) {
        Timber.d("[AMLLLyrics] [$debugSource#$instanceId] 注入 WebSocket 桥接代码")
        
        // 注入 JavaScript 代码，让前端可以发送消息到 Android WebSocket 客户端
        view.evaluateJavascript(
            """
            (function() {
                if (!window.AndroidWebSocketBridge) {
                    window.AndroidWebSocketBridge = {
                        send: function(message) {
                            // 通过 JavascriptInterface 发送到 Android
                            if (window.Android && window.Android.sendWebSocketMessage) {
                                window.Android.sendWebSocketMessage(JSON.stringify(message));
                            }
                        }
                    };
                    console.log('[AMLL Bridge] WebSocket bridge injected');
                }
            })();
            """.trimIndent(),
            null
        )
    }
    
    // WebSocket 状态同步逻辑 - 当歌曲信息或播放状态变化时立即发送
    LaunchedEffect(musicId, musicName, albumName, artistName, duration, isPlaying) {
        if (AppSettings.isWebSocketProtocolEnabled(context) && isWebSocketConnected) {
            // 当歌曲信息或播放状态变化时，立即发送新状态（播放进度惯性除外）
            sendPlaybackStatusToWebSocket(currentTime, isPlaying)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            Timber.i("[AMLLLyrics] [$debugSource#$instanceId] Creating AMLL WebView, onLineSeek=${onLineSeekState.value != null}")
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                // 设置 WebView 的 LayoutParams 为 MATCH_PARENT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        isPageReady = false
                        isBridgeReady = false
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        lastLyrics = null
                        lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        Timber.d("[AMLLLyrics] [$debugSource#$instanceId] WebView page started: $url")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        isPageReady = true
                        // Force one re-sync after page finishes to avoid losing early bridge calls.
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        // 页面刷新结束时不主动清空 lastLyrics，让我们知道是否还有有效歌词
                        // lastLyrics = null
                        // 页面刷新完成后如果我们之前有歌词 JSON 且当前仍然有 lyrics（不是因歌曲切换而清空），先立刻重新下发
                        if (isBridgeReady && lastLyricsPayload != null && lastLyrics != null) {
                            Timber.d("[AMLLLyrics] [$debugSource#$instanceId] reapplying lyrics payload after page finish")
                            view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lastLyricsPayload);", null)
                        }
                        // 不清空 payload，让 update() 继续根据 lyrics 对象决定重新生成
                        // lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        // 确保页面加载后背景仍然透明
                        view.setBackgroundColor(Color.TRANSPARENT)
                        
                        // 注入 WebSocket 桥接代码（如果启用了 WebSocket）
                        if (isWebSocketConnected) {
                            injectWebSocketBridge(view)
                        }
                        
                        Timber.d("[AMLLLyrics] [$debugSource#$instanceId] WebView page finished: $url")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val logMessage = "[AMLLLyrics] [WebView] [$debugSource#$instanceId] JS Console(@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}): ${consoleMessage.message()}"
                        when (consoleMessage.messageLevel()) {
                            ConsoleMessage.MessageLevel.DEBUG -> Timber.d(logMessage)
                            ConsoleMessage.MessageLevel.LOG -> Timber.i(logMessage)
                            ConsoleMessage.MessageLevel.WARNING -> Timber.w(logMessage)
                            ConsoleMessage.MessageLevel.ERROR -> Timber.e(logMessage)
                            else -> Timber.d(logMessage)
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }
                // WebView 安全配置
                // 已弃用的 WebView 配置，但为了保持兼容性暂时保留
                @Suppress("DEPRECATION")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    // 仅允许从本地文件 URI 读取资源（用于专辑封面）
                    // 禁用跨文件访问以提升安全性
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    // 禁用缓存确保每次加载最新的文件
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                }

                // 透明 WebView 配置，允许宿主 Compose 层的专辑图背景透出
                // 先设置背景透明
                setBackgroundColor(Color.TRANSPARENT)
                // 使用 NONE 让 View 自行决定渲染方式，通常会使用硬件加速
                // 同时避免软件渲染导致的帧率问题
                setLayerType(View.LAYER_TYPE_NONE, null)
                
                // 强制清除所有缓存数据，确保加载最新的 HTML 和 JS
                clearAllCache()

                // keep a reference to the WebView so we can send immediate commands back to
                // the javascript bridge when the user initiates a seek via clicking a lyric.
                val webViewRef = this

                addJavascriptInterface(
                    AMLLInterface(
                        debugSource,
                        instanceId,
                        onLineSeekState.value,
                        webSocketClient = webSocketClient, // 传递 WebSocket 客户端引用
                        onSeekRequested = { seekTime ->
                            // schedule a UI-thread action so that the webview can immediately
                            // acknowledge the seek and prevent the "lyrics running around" effect.
                            webViewRef.post {
                                // tell the JS player we are seeking so it can suspend auto-scroll
                                webViewRef.evaluateJavascript(
                                    "window.callPlayer && window.callPlayer('setIsSeeking', true);",
                                    null
                                )

                                // update the webview time to the target position right away. this
                                // reduces the window where the old time would cause the view to
                                // scroll back to the previous line before the new position arrives
                                webViewRef.evaluateJavascript(
                                    "window.updateTime && window.updateTime($seekTime);",
                                    null
                                )
                            }
                        },
                        isPlayingProvider = { isPlayingState.value },
                        onFrontendReady = {
                            webViewRef.post {
                                if (!isBridgeReady) {
                                    isBridgeReady = true
                                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Frontend bridge reported ready")
                                }
                            }
                        }
                    ),
                    "Android"
                )
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] JavascriptInterface added as Android")

                setOnClickListener {
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] WebView onClick listener fired")
                    onLyricsClickState.value?.invoke()
                }

                loadUrl("file:///android_asset/amll/index.html")

                post {
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] WebView size after layout: width=$width, height=$height, measuredWidth=$measuredWidth, measuredHeight=$measuredHeight")
                }

                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] WebView initialized with URL: file:///android_asset/amll/index.html")
            }
        },
        update = { view ->
            if (!isPageReady) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge skipped: page not ready")
                return@AndroidView
            }
            if (!isBridgeReady) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge skipped: frontend bridge not ready")
                return@AndroidView
            }

            Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Update callback - WebView actual size: width=${view.width}, height=${view.height}, measuredWidth=${view.measuredWidth}, measuredHeight=${view.measuredHeight}")

            // 立即更新时间，减少歌词行激活延迟
            Timber.d("[AMLLLyrics] [WebView] [$debugSource#$instanceId] Bridge call: updateTime($currentTime)")
            view.evaluateJavascript("window.updateTime && window.updateTime($currentTime);", null)
            
            // 同时通过 WebSocket 发送到外部服务
            sendPlaybackStatusToWebSocket(currentTime, isPlayingState.value)

            val modeValue = if (renderMode == AMLLRenderMode.DOM) "dom" else "dom-lite"
            if (lastModeValue != modeValue) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge call: setRenderMode($modeValue)")
                view.evaluateJavascript("window.setRenderMode && window.setRenderMode('$modeValue');", null)
                lastModeValue = modeValue
            }

            val configuredFps = AppSettings.getAmllAnimationFps(view.context).coerceIn(15, 60)
            val fpsValue = if (renderMode == AMLLRenderMode.DOM_LITE) configuredFps.coerceAtMost(45) else configuredFps

            val backgroundProfile = if (renderMode == AMLLRenderMode.DOM) {
                """{"renderer":"pixi","fps":$fpsValue,"flowSpeed":2.35,"renderScale":0.9,"staticMode":false,"lowFreqVolume":1.0}"""
            } else {
                """{"renderer":"pixi","fps":$fpsValue,"flowSpeed":1.4,"renderScale":0.65,"staticMode":false,"lowFreqVolume":1.0}"""
            }
            if (lastBackgroundProfileValue != backgroundProfile) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge call: configureBackgroundEffect(profile=$backgroundProfile)")
                view.evaluateJavascript(
                    "window.configureBackgroundEffect && window.configureBackgroundEffect($backgroundProfile);",
                    null
                )
                lastBackgroundProfileValue = backgroundProfile
            }

            val motionConfig = """{
                "enableSpring":${AppSettings.isAmllAnimationSpringEnabled(view.context)},
                "enableScale":${AppSettings.isAmllAnimationScaleEnabled(view.context)},
                "enableBlur":${AppSettings.isAmllAnimationBlurEnabled(view.context)},
                "hidePassedLines":${AppSettings.isAmllAnimationHidePassedLinesEnabled(view.context)},
                "wordFadeWidth":${AppSettings.getAmllAnimationWordFadeWidth(view.context)}
            }""".trimIndent().replace("\n", "")

            if (lastMotionConfigValue != motionConfig) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge call: configureLyricMotion(profile=$motionConfig)")
                view.evaluateJavascript("window.configureLyricMotion && window.configureLyricMotion($motionConfig);", null)
                lastMotionConfigValue = motionConfig
            }

            // 只在 lyrics 对象引用改变时才重新构建 JSON（避免每秒都构建）
            if (lyrics !== lastLyrics) {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Lyrics changed: ${lyrics?.lines?.size ?: 0} lines")
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    val lyricsJson = buildLyricsJson(lyrics)
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge call: updateLyrics(lines=${lyrics.lines.size})")
                    // 添加详细日志，显示前几行歌词内容
                    lyrics.lines.take(3).forEachIndexed { idx, line ->
                        Timber.d("[AMLLLyrics]   Line $idx: text='${line.text}', words=${line.words.size}, isBG=${line.isBG}")
                    }
                    view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lyricsJson);", null)
                    lastLyricsPayload = lyricsJson
                                
                    // 通过 WebSocket 发送歌词更新（V2 协议）
                    if (isWebSocketConnected) {
                        try {
                            // V2 协议格式：SetLyric 使用 Ttml 格式
                            // {"update":"SetLyric","value":{"format":"Ttml","data":"..."}}
                            val lyricMessage = """{"update":"SetLyric","value":{"format":"Ttml","data":$lyricsJson}}"""
                            webSocketClient.send(lyricMessage)
                            Timber.d("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] 已通过 WebSocket 发送歌词")
                        } catch (e: Exception) {
                            Timber.e("[AMLLLyrics] [WebSocket] [$debugSource#$instanceId] 通过 WebSocket 发送歌词失败", e)
                        }
                    }
                } else {
                    // 如果 lyrics 为空或 null，注入测试歌词以便调试
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] No lyrics provided, injecting test lyrics")
                    val testLyricsJson = """{"metadata":{"title":"Test","artist":"AMLL"},"lines":[{"startTime":0,"endTime":3000,"text":"测试歌词","translatedLyric":"","romanLyric":"","words":[{"word":"测试","startTime":0,"endTime":1500},{"word":"歌词","startTime":1500,"endTime":3000}],"isBG":false,"isDuet":false},{"startTime":3000,"endTime":6000,"text":"第二行歌词","translatedLyric":"","romanLyric":"","words":[{"word":"第二行","startTime":3000,"endTime":4500},{"word":"歌词","startTime":4500,"endTime":6000}],"isBG":false,"isDuet":false}]}"""
                    view.evaluateJavascript("window.updateLyrics && window.updateLyrics($testLyricsJson);", null)
                    lastLyricsPayload = testLyricsJson
                }
                lastLyrics = lyrics
            } else {
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Lyrics reference unchanged")
            }

            if (lastAlbumArtUri != albumArtUri) {
                // 将 file:// URI 转换为 base64 data URL，因为 WebView 的 Fetch API 不支持 file:// 协议
                val albumArtDataUrl = convertFileUriToDataUrl(view.context, albumArtUri ?: "")
                val escapedAlbumUri = escapeJsString(albumArtDataUrl ?: "")
                val uriDesc = if (albumArtDataUrl.isNullOrBlank()) "empty" else "present (${albumArtDataUrl.length} chars)"
                Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Bridge call: updateAlbumArt(uri=$uriDesc)")
                view.evaluateJavascript("window.updateAlbumArt && window.updateAlbumArt(\"$escapedAlbumUri\");", null)
                lastAlbumArtUri = albumArtUri
            }

            val configuredFontFamily = AppSettings.getAmllFontFamily(view.context)
            val fontFiles = AppSettings.getAmllFontFiles(view.context)
                .filter { it.absolutePath.isNotBlank() }
                .mapNotNull { item ->
                    val file = File(item.absolutePath)
                    if (!file.exists()) return@mapNotNull null
                    FontWebEntry(
                        id = item.id,
                        sortKey = item.fontFamilyName,
                        familyName = buildRuntimeFontFamilyName(item.fontFamilyName, item.id),
                        sourcePath = file.absolutePath,
                        sourceKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
                    )
                }

            val enabledIds = AppSettings.getEnabledAmllFontFileIds(view.context)
            val preferredOrder = parsePreferredFontOrder(configuredFontFamily)
            val enabledFamilies = fontFiles
                .filter { enabledIds.contains(it.id) }
                .sortedWith(
                    compareBy<FontWebEntry> { fontSortPriority(it.sortKey, preferredOrder) }
                        .thenBy { it.sortKey.lowercase() }
                        .thenBy { it.id }
                )
                .map { it.familyName }
                .distinct()

            val effectiveFamily = if (enabledFamilies.isNotEmpty()) {
                val enabledStack = enabledFamilies.joinToString(", ") { "\"$it\"" }
                "$enabledStack, $configuredFontFamily"
            } else {
                configuredFontFamily
            }

            val fontSignature = buildString {
                append(effectiveFamily)
                append("|")
                append(fontFiles.joinToString(";") { "${it.id}:${it.familyName}:${it.sourceKey}" })
                append("|")
                append(enabledFamilies.joinToString(","))
            }

            if (lastFontConfigSignature != fontSignature) {
                val scriptEntries = fontFiles.mapNotNull { entry ->
                    val dataUrl = convertFontFileToDataUrl(File(entry.sourcePath)) ?: return@mapNotNull null
                    FontScriptEntry(
                        familyName = entry.familyName,
                        uri = dataUrl
                    )
                }
                val script = buildApplyFontScript(effectiveFamily, scriptEntries)
                Timber.d(
                    "[AMLLLyrics] [$debugSource#$instanceId] Bridge call: applyFontSettings(enabled=${enabledFamilies.size}, files=${fontFiles.size})"
                )
                view.evaluateJavascript(script, null)
                lastFontConfigSignature = fontSignature
            }
        },
        onRelease = { view ->
            // 当组件被销毁时，销毁 WebView 以避免内存泄漏
            Timber.i("[AMLLLyrics] [$debugSource] Destroying AMLL WebView")
            view.stopLoading()
            view.clearHistory()
            view.clearCache(true)
            view.removeJavascriptInterface("Android")
            view.destroy()
        }
    )
}

private data class FontWebEntry(
    val id: String,
    val sortKey: String,
    val familyName: String,
    val sourcePath: String,
    val sourceKey: String
)

private data class FontScriptEntry(
    val familyName: String,
    val uri: String
)

private fun buildRuntimeFontFamilyName(baseFamilyName: String, fontId: String): String {
    val base = baseFamilyName
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .ifBlank { "AMLL_FONT" }
    return "${base}_$fontId"
}

private fun parsePreferredFontOrder(configuredFontFamily: String): List<String> {
    return configuredFontFamily
        .split(',')
        .map { normalizeFontToken(it) }
        .filter { it.isNotBlank() }
}

private fun fontSortPriority(sortKey: String, preferredOrder: List<String>): Int {
    if (preferredOrder.isEmpty()) return Int.MAX_VALUE
    val normalizedSortKey = normalizeFontToken(sortKey)
    for (index in preferredOrder.indices) {
        val preferred = preferredOrder[index]
        if (preferred.isBlank()) continue
        if (normalizedSortKey.contains(preferred) || preferred.contains(normalizedSortKey)) {
            return index
        }
    }
    return Int.MAX_VALUE
}

private fun normalizeFontToken(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}

private fun buildApplyFontScript(effectiveFamily: String, files: List<FontScriptEntry>): String {
    // 将字体家族名称转换为 JSON 安全的字符串
    val familyJson = "\"${escapeJsStringForJson(effectiveFamily)}\""
    
    // 构建文件数组的 JSON 表示
    val filesArrayJson = if (files.isEmpty()) {
        "[]"
    } else {
        val filesEntries = files.joinToString(",") { entry ->
            "{familyName:\"${escapeJsStringForJson(entry.familyName)}\",uri:\"${escapeJsStringForJson(entry.uri)}\"}"
        }
        "[$filesEntries]"
    }

    return buildString {
        append("(function(){")
        append("var effectiveFamily=$familyJson;")
        append("var files=$filesArrayJson;")
        append("var styleId='amll-dynamic-font-face-style';")
        append("var styleNode=document.getElementById(styleId);")
        append("if(!styleNode){styleNode=document.createElement('style');styleNode.id=styleId;document.head.appendChild(styleNode);}")
        append("var css='';")
        append("for(var i=0;i<files.length;i+=1){var item=files[i];if(!item||!item.familyName||!item.uri)continue;css+='@font-face{font-family:\"'+item.familyName+'\";src:url(\"'+item.uri+'\");font-display:swap;}';}")
        append("styleNode.textContent=css;")
        append("document.documentElement.style.setProperty('--amll-user-font-family',effectiveFamily);")
        append("document.documentElement.style.setProperty('--amll-lp-font-family','var(--amll-user-font-family)');")
        append("})();")
    }
}

private fun escapeJsStringForJson(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

private fun escapeJsString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

private fun buildLyricsJson(lyrics: TTMLLyrics): String {
    val bgLines = lyrics.lines.filter { it.isBG }
    val bgWithTranslation = bgLines.count { !it.translation.isNullOrBlank() }
    val bgWithRoman = bgLines.count { !it.transliteration.isNullOrBlank() }
    val sampleBg = bgLines.firstOrNull()
    Timber.d("[BG-LYRICS-DEBUG] buildLyricsJson summary: total=${lyrics.lines.size}, bg=${bgLines.size}, bgWithTrans=$bgWithTranslation, bgWithRoman=$bgWithRoman, sampleBg='${sampleBg?.text ?: ""}', sampleTrans='${sampleBg?.translation ?: ""}'")

    // 调试日志：限制在 10 行以内，超出的降级为 v 级别
    var debugCount = 0
    
    val linesJson = lyrics.lines.joinToString(",") { line ->
        val text = line.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val translation = line.translation?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        val transliteration = line.transliteration?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        
        // 构建 words 数组
        val wordsJson = if (line.words.isNotEmpty()) {
            line.words.joinToString(",") { word ->
                val wordText = word.word.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"word":"$wordText","startTime":${word.startTime},"endTime":${word.endTime}}"""
            }
        } else {
            // 如果没有逐词信息，则使用整行文本作为单词
            val wordText = text.replace("\"", "\\\"")
            """{"word":"$wordText","startTime":${line.startTime},"endTime":${line.endTime}}"""
        }
        
        // 调试日志：只记录前 5 行
        if (line.words.isNotEmpty()) {
            if (debugCount < 5) {
                Timber.d("[AMLLLyrics] Building JSON for line: '${line.text}' with ${line.words.size} words")
                debugCount++
            }
        }
        
        // 调试背景歌词的数据传递
        if (line.isBG) {
            Timber.d("[BG-LYRICS-DEBUG] JSON for BG line: text='$text' translation='$translation' roman='$transliteration' isBG=${line.isBG}")
        }
        
        """{
            "startTime":${line.startTime},
            "endTime":${line.endTime},
            "text":"$text",
            "translatedLyric":"$translation",
            "romanLyric":"$transliteration",
            "words":[$wordsJson],
            "isBG":${line.isBG},
            "isDuet":${line.isDuet}
        }"""
    }

    val title = lyrics.metadata.title.replace("\\", "\\\\").replace("\"", "\\\"")
    val artist = lyrics.metadata.artist.replace("\\", "\\\\").replace("\"", "\\\"")

    return """{"metadata":{"title":"$title","artist":"$artist"},"lines":[$linesJson]}"""
}

class AMLLInterface(
    private val debugSource: String,
    private val instanceId: Int,
    private val onLineSeek: ((Long) -> Unit)? = null,
    private val webSocketClient: com.amll.droidmate.websocket.AMLLWebSocketClient? = null, // WebSocket 客户端引用
    private val onSeekRequested: ((Long) -> Unit)? = null,
    private val isPlayingProvider: () -> Boolean = { true },
    private val onFrontendReady: (() -> Unit)? = null
) {
    @JavascriptInterface
    fun log(message: String, level: String = "debug") {
        val levelUpper = level.uppercase()
        when (levelUpper) {
            "DEBUG" -> Timber.d("[AMLLLyrics] [WebView] JS: $message")
            "INFO" -> Timber.i("[AMLLLyrics] [WebView] JS: $message")
            "WARN" -> Timber.w("[AMLLLyrics] [WebView] JS: $message")
            "ERROR" -> Timber.e("[AMLLLyrics] [WebView] JS: $message")
            else -> Timber.d("[AMLLLyrics] [WebView] JS: $message")
        }
    }

    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTime: Long) {
        Timber.i("[AMLLLyrics] [$debugSource#$instanceId] User clicked lyric line: index=$lineIndex, startTime=$startTime, callbackPresent=${onLineSeek != null}")
        onSeekRequested?.invoke(startTime)
        onLineSeek?.invoke(startTime)
    }

    @JavascriptInterface
    fun isPlaying(): Boolean {
        return isPlayingProvider()
    }

    @JavascriptInterface
    fun onFrontendReady() {
        Timber.d("[AMLLLyrics] [$debugSource#$instanceId] Frontend ready callback received")
        onFrontendReady?.invoke()
    }
    
    @JavascriptInterface
    fun sendWebSocketMessage(message: String) {
        // 通过 WebSocket 发送到外部 AMLL 服务
        Timber.d("[AMLLLyrics] [$debugSource#$instanceId] 发送 WebSocket 消息：$message")
        
        try {
            // 解析消息并转发到 WebSocket 客户端
            val jsonObject = org.json.JSONObject(message)
            val type = jsonObject.optString("type")
            
            when (type) {
                "ping" -> {
                    // 响应 ping 消息
                    webSocketClient?.send("{\"type\":\"pong\"}")
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] 已响应 ping 消息")
                }
                "seek" -> {
                    // 处理 seek 命令（如果需要）
                    val time = jsonObject.optLong("time", 0L)
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] 收到 seek 命令：time=$time")
                }
                else -> {
                    // 其他类型的消息直接转发
                    webSocketClient?.send(message)
                    Timber.d("[AMLLLyrics] [$debugSource#$instanceId] 已转发 WebSocket 消息：type=$type")
                }
            }
        } catch (e: Exception) {
            Timber.e("[WebView] [$debugSource#$instanceId] 发送 WebSocket 消息失败", e)
        }
    }
}

private fun convertFontFileToDataUrl(file: File): String? {
    return try {
        val bytes = file.readBytes()
        val mimeType = getFontMimeType(file.name) ?: "font/ttf"
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        "data:$mimeType;base64,$base64"
    } catch (e: Exception) {
        Timber.e("[AMLLLyrics] [WebView] Failed to convert font to data URL: ${file.absolutePath}", e)
        null
    }
}

private fun getFontMimeType(fileName: String): String? {
    return when {
        fileName.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
        fileName.endsWith(".otf", ignoreCase = true) -> "font/otf"
        fileName.endsWith(".woff", ignoreCase = true) -> "font/woff"
        fileName.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
        else -> null
    }
}

/**
 * 将 file:// URI 转换为 base64 data URL，以便 WebView 能够加载本地图片
 */
private fun convertFileUriToDataUrl(context: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) {
        return null
    }
    
    return try {
        val inputStream = when {
            uriString.startsWith("file://") -> {
                val path = uriString.removePrefix("file://")
                File(path).inputStream()
            }
            uriString.startsWith("content://") -> {
                val uri = android.net.Uri.parse(uriString)
                context.contentResolver.openInputStream(uri)
            }
            else -> {
                Timber.w("[AMLLLyrics] [WebView] Unsupported URI scheme: $uriString")
                return uriString // 直接返回原始字符串（可能是 data URL）
            }
        }
        
        inputStream?.use { stream ->
            val bytes = stream.readBytes()
            val mimeType = getMimeType(uriString) ?: "image/jpeg"
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:$mimeType;base64,$base64"
        }
    } catch (e: Exception) {
        Timber.e("[AMLLLyrics] [WebView] Failed to convert file URI to data URL: $uriString", e)
        null
    }
}

/**
 * 根据文件扩展名获取 MIME 类型
 */
private fun getMimeType(uriString: String): String? {
    return when {
        uriString.endsWith(".png", ignoreCase = true) -> "image/png"
        uriString.endsWith(".jpg", ignoreCase = true) || uriString.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        uriString.endsWith(".gif", ignoreCase = true) -> "image/gif"
        uriString.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg" // 默认为 JPEG
    }
}

/**
 * 清除 WebView 的所有缓存数据
 */
private fun WebView.clearAllCache() {
    try {
        // 清除内存缓存
        clearCache(true)
        
        // 清除 DOM 存储
        settings.domStorageEnabled = false
        settings.domStorageEnabled = true
        
        Timber.d("[AMLLLyrics] WebView cache cleared")
    } catch (e: Exception) {
        Timber.d("[AMLLLyrics] Failed to clear WebView cache: ${e.message}")
    }
}
