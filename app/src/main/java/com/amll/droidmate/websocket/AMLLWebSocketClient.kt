package com.amll.droidmate.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import com.amll.droidmate.domain.model.TTMLLyrics

/**
 * AMLL WebSocket 客户端（单例模式）
 * 
 * 在 Android 端作为 WebSocket 客户端连接到外部 AMLL 服务
 * 实现双向通信：发送播放状态，接收控制命令
 * 
 * 使用方式：
 * val client = AMLLWebSocketClient.getInstance()
 */
class AMLLWebSocketClient private constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    
    companion object {
        @Volatile
        private var instance: AMLLWebSocketClient? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(): AMLLWebSocketClient {
            return instance ?: synchronized(this) {
                instance ?: AMLLWebSocketClient().also { instance = it }
            }
        }
        
        /**
         * 重置单例（用于测试或重新初始化）
         */
        fun resetInstance() {
            instance?.destroy()
            instance = null
        }
    }
    
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(message: String)
        fun onError(error: Throwable)
        
        /**
         * 当 WebSocket 连接成功时，返回当前播放状态用于同步
         * @return 包含歌曲信息、进度、状态的 PlayState 对象，如果无播放内容则返回 null
         */
        fun getCurrentPlayState(): PlayState? = null
    }
    
    /**
     * 播放状态数据类
     */
    data class PlayState(
        val musicId: String,
        val musicName: String,
        val albumName: String,
        val artistName: String,
        val duration: Long,
        val progress: Long,
        val isPlaying: Boolean,
        val ttmlLyric: String? = null
    )
    
    // 支持多个监听器
    private val listeners = mutableListOf<Listener>()
    
    /**
     * 添加消息监听器
     */
    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
    
    /**
     * 创建一个简单的状态监听器（仅用于 UI 显示连接状态）
     * @param onStateChanged 状态变化回调 (true=已连接，false=未连接)
     * @param onErrorCallback 错误回调（可选）
     */
    fun createStateListener(
        onStateChanged: (Boolean) -> Unit,
        onErrorCallback: ((Throwable) -> Unit)? = null
    ): Listener {
        return object : Listener {
            override fun onConnected() {
                onStateChanged(true)
            }
            
            override fun onDisconnected() {
                onStateChanged(false)
            }
            
            override fun onMessageReceived(message: String) {}
            
            override fun onError(error: Throwable) {
                onStateChanged(false)
                onErrorCallback?.invoke(error)
            }
            
            override fun getCurrentPlayState(): PlayState? = null
        }
    }
    
    /**
     * 创建完整功能的监听器（支持歌曲信息同步和命令处理）
     * @param debugSource 调试标签
     * @param musicId 歌曲 ID
     * @param musicName 歌曲名称
     * @param albumName 专辑名称
     * @param artistName 艺术家名称
     * @param duration 歌曲时长
     * @param currentTime 当前进度
     * @param isPlaying 是否正在播放
     * @param lyrics 歌词数据
     * @param onConnectedCallback 连接成功后的额外操作（可选）
     * @param onCommandReceived 收到命令时的处理（可选）
     * @param onErrorCallback 错误回调（可选）
     */
    fun createFullFeatureListener(
        debugSource: String,
        musicId: String,
        musicName: String,
        albumName: String,
        artistName: String,
        duration: Long,
        currentTime: Long,
        isPlaying: Boolean,
        lyrics: TTMLLyrics?,
        rawTtmlContent: String? = null,
        onConnectedCallback: (() -> Unit)? = null,
        onCommandReceived: ((String, kotlinx.serialization.json.JsonObject?) -> Unit)? = null,
        onErrorCallback: ((Throwable) -> Unit)? = null
    ): Listener {
        return object : Listener {
            override fun onConnected() {
                Timber.i("[WebSocket] WebSocket connected")
                Timber.d("[WebSocket] Current song info: musicId=$musicId, musicName=$musicName, artist=$artistName")
                
                // 执行额外的连接后操作
                onConnectedCallback?.invoke()
            }
            
            override fun onDisconnected() {
                Timber.w("[WebSocket] WebSocket disconnected")
            }
            
            override fun onMessageReceived(message: String) {
                Timber.d("[WebSocket] Received WebSocket message: $message")
                
                // 解析并处理来自服务器的命令
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(message)
                    val type = json.jsonObject["type"]?.jsonPrimitive?.content
                    
                    if (type == "command") {
                        val valueObj = json.jsonObject["value"]?.jsonObject
                        val command = valueObj?.get("command")?.jsonPrimitive?.content
                        
                        Timber.i("[PlaybackControl] Received command: $command")
                        Timber.d("[WebSocket] onCommandReceived reference: $onCommandReceived")
                        
                        if (onCommandReceived != null) {
                            Timber.d("[WebSocket] Preparing to call onCommandReceived, command: $command")
                            onCommandReceived.invoke(command ?: "unknown", valueObj)
                            Timber.d("[WebSocket] onCommandReceived executed")
                        } else {
                            Timber.w("[WebSocket] onCommandReceived is null, skipping command processing")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e("[WebSocket] Failed to parse command: ${e.message}", e)
                }
            }
            
            override fun onError(error: Throwable) {
                Timber.e("[WebSocket] WebSocket error", error)
                onErrorCallback?.invoke(error)
            }
            
            override fun getCurrentPlayState(): PlayState? {
                // 只有当有有效的歌曲信息时才返回 PlayState
                val validMusicId = musicId.takeIf { it.isNotEmpty() && it != "unknown" }
                val validMusicName = musicName.takeIf { it.isNotEmpty() && it != "Unknown" && it != "等待播放" }
                
                if (validMusicId == null || validMusicName == null) {
                    Timber.d("[WebSocket] getCurrentPlayState: invalid song info (musicId=$musicId, musicName=$musicName)")
                    return null
                }
                
                val ttmlContent = rawTtmlContent?.takeIf { it.isNotBlank() } ?: lyrics?.let {
                    com.amll.droidmate.data.converter.TTMLConverter.toTTMLString(it).takeIf { serialized -> serialized.isNotBlank() }
                }
                
                val state = PlayState(
                    musicId = validMusicId,
                    musicName = validMusicName,
                    albumName = albumName,
                    artistName = artistName,
                    duration = duration,
                    progress = currentTime,
                    isPlaying = isPlaying,
                    ttmlLyric = ttmlContent
                )
                
                Timber.d("[WebSocket] getCurrentPlayState returns:")
                Timber.d("[WebSocket]  - musicId: ${state.musicId}")
                Timber.d("[WebSocket]  - musicName: ${state.musicName}")
                Timber.d("[WebSocket]  - artistName: ${state.artistName}")
                Timber.d("[WebSocket]  - hasLyrics: ${!state.ttmlLyric.isNullOrBlank()}")
                Timber.d("[WebSocket]  - isPlaying: ${state.isPlaying}")
                Timber.d("[WebSocket]  - progress: ${state.progress}ms")
                
                return state
            }
        }
    }
    
    // ==================== 内部辅助函数 ====================
    
    /**
     * XML 转义
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    // ==================== WebSocket 投送辅助函数 ====================
    
    /**
     * 发送歌曲信息到 WebSocket 服务器
     * @param musicId 歌曲 ID
     * @param musicName 歌曲名称
     * @param albumName 专辑名称
     * @param artistName 艺术家名称
     * @param duration 歌曲时长
     */
    fun sendMusicInfo(
        musicId: String,
        musicName: String,
        albumName: String,
        artistName: String,
        duration: Long
    ) {
        if (!isConnected) {
            Timber.w("[WebSocket] Not connected, skipping sending song info")
            return
        }
        
        try {
            val message = WsProtocolV2Helper.createSetMusicUpdate(
                musicId = musicId,
                musicName = musicName,
                albumName = albumName,
                artists = listOf(WsProtocolV2Helper.Artist("1", artistName)),
                duration = duration
            )
            send(message)
            Timber.d("[WebSocket] Sent song info: $musicName")
        } catch (e: Exception) {
            Timber.e("[WebSocket] Failed to send song info: ${e.message}", e)
        }
    }
    
    /**
     * 发送歌词到 WebSocket 服务器
     * @param ttmlContent TTML 格式的歌词内容
     */
    fun sendLyrics(ttmlContent: String) {
        if (!isConnected) {
            Timber.w("[WebSocket] Not connected, skipping sending lyrics")
            return
        }
        
        if (ttmlContent.isBlank()) {
            Timber.w("[LyricsMatcher] Lyrics content is empty, skipping send")
            return
        }
        
        try {
            val message = WsProtocolV2Helper.createTTMLLyricUpdate(ttmlContent)
            send(message)
            Timber.d("[WebSocket] Sent lyrics: size=${ttmlContent.length} chars")
            // 输出前 200 个字符用于调试
            Timber.d("[WebSocket]  - Lyrics preview: ${ttmlContent.take(200)}...")
        } catch (e: Exception) {
            Timber.e("[WebSocket] Failed to send lyrics: ${e.message}", e)
            // 通知所有监听器发生了错误
            listeners.forEach { listener ->
                try {
                    listener.onError(e)
                } catch (ex: Exception) {
                    Timber.e("[WebSocket] 监听器 onError 异常", ex)
                }
            }
        }
    }
    
    /**
     * 发送专辑图到 WebSocket 服务器
     * @param albumArtDataUrl Base64 编码的专辑图数据 URL（格式：data:image/jpeg;base64,/9j/...）
     */
    fun sendAlbumArt(albumArtDataUrl: String) {
        if (!isConnected) {
            Timber.w("[WebSocket] Not connected, skipping sending album art")
            return
        }
        
        if (albumArtDataUrl.isBlank()) {
            Timber.d("[AlbumArtExtractor] Album art is empty, skipping send")
            return
        }
        
        try {
            val message = WsProtocolV2Helper.createAlbumArtUpdate(albumArtDataUrl)
            send(message)
            Timber.d("[WebSocket] Sent album art: size=${albumArtDataUrl.length} chars")
        } catch (e: Exception) {
            Timber.e("[WebSocket] Failed to send album art", e)
        }
    }
    private var webSocket: WebSocket? = null
    // 使用固定的本地端口（每次启动时固定）
    private val localPort = 50000 + (System.currentTimeMillis() % 1000).toInt()
    
    // 自定义 SocketFactory 用于绑定本地端口
    private class FixedPortSocketFactory(private val localPort: Int) : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket()
            // 只绑定端口，不绑定特定 IP（让系统选择最佳本地 IP）
            try {
                socket.bind(InetSocketAddress(localPort))
                Timber.d("[Network] Socket bound to local port: $localPort")
            } catch (e: Exception) {
                Timber.w("[Network] Cannot bind to port $localPort, using system default: ${e.message}", e)
            }
            return socket
        }
        
        override fun createSocket(host: String, port: Int): Socket {
            return createSocket().apply { connect(InetSocketAddress(host, port)) }
        }
        
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            return createSocket().apply { connect(InetSocketAddress(host, port)) }
        }
        
        override fun createSocket(host: InetAddress, port: Int): Socket {
            return createSocket().apply { connect(InetSocketAddress(host, port)) }
        }
        
        override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            return createSocket().apply { connect(InetSocketAddress(host, port)) }
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // 心跳包保持连接
        .socketFactory(FixedPortSocketFactory(localPort))
        .build()
    
    // 连接状态管理（public，供 UI 直接查询）
    @get:JvmName("getConnectionState")
    var isConnected = false
        get() = field
        set(value) {
            field = value
            // 状态变化时通知所有监听器
            if (value) {
                listeners.forEach { 
                    try {
                        it.onConnected()
                    } catch (e: Exception) {
                        Timber.e("[WebSocket] 监听器 onConnected 异常", e)
                    }
                }
            } else {
                listeners.forEach { 
                    try {
                        it.onDisconnected()
                    } catch (e: Exception) {
                        Timber.e("[WebSocket] 监听器 onDisconnected 异常", e)
                    }
                }
            }
        }
    private var serverUrl: String? = null
    private var isHandshakeComplete = false // 标记是否已完成握手
    private var negotiatedProtocolVersion: WsProtocolVersion = WsProtocolVersion.V2 // 默认 V2
    private val config = WsProtocolConfig() // 协议配置
    private var heartbeatJob: kotlinx.coroutines.Job? = null // 心跳任务
    
    /**
     * 设置消息监听器（已废弃，请使用 addListener）
     * @deprecated 使用 addListener 代替
     */
    @Deprecated("Use addListener instead", ReplaceWith("addListener(listener)"))
    fun setListener(listener: Listener) {
        addListener(listener)
    }
    
    /**
     * 连接到 WebSocket 服务器
     * 
     * @param url WebSocket 服务器地址 (ws://host:port 或 wss://host:port)
     * @param forceReconnect 是否强制重连（即使已连接）
     */
    fun connect(url: String, forceReconnect: Boolean = false) {
        // 如果已连接且不需要重连，则跳过
        if (isConnected && !forceReconnect) {
            Timber.d("[WebSocket] Already connected, skipping reconnect: $url")
            return
        }
        
        if (isConnected && forceReconnect) {
            Timber.i("[WebSocket] Forced reconnect, disconnecting old connection")
            disconnect()
        } else if (isConnected) {
            Timber.w("[WebSocket] Already connected to WebSocket server, disconnecting old connection")
            disconnect()
        }
        
        serverUrl = url
        
        scope.launch {
            try {
                Timber.d("[WebSocket] Starting to connect to server: $url")
                
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Timber.i("[WebSocket] Connection successful, preparing handshake")
                        isConnected = true
                        
                        // ✅ 每次连接都必须发送 Initialize 握手（无论是否是重连）
                        if (config.sendInitialize) {
                            // 直接同步发送，不通过 scope.launch
                            val initializeMessage = """{"type":"initialize"}"""
                            webSocket.send(initializeMessage)
                            Timber.d("[WebSocket] Sent V2 Initialize handshake message (synchronous)")
                            
                            // 短暂延迟确保服务器收到
                            kotlinx.coroutines.runBlocking {
                                kotlinx.coroutines.delay(50) // 50ms 延迟
                            }
                            
                            isHandshakeComplete = true
                            Timber.i("[WebSocket] Handshake completed (V2 protocol)")
                            
                            // Step 2: 握手完成后才通知监听器并发送状态
                            var stateSent = false
                            Timber.d("[WebSocket] Starting to traverse listeners, total: ${listeners.size}")
                            listeners.forEachIndexed { index, listener ->
                                try {
                                    Timber.d("[WebSocket] Calling listener #$index.onConnected()")
                                    listener.onConnected()
                                    
                                    // 获取并发送当前播放状态
                                    Timber.d("[WebSocket] Calling listener #$index.getCurrentPlayState()")
                                    val playState = listener.getCurrentPlayState()
                                    if (playState != null) {
                                        Timber.d("[WebSocket] Listener #$index returned valid play state: ${playState.musicName}, musicId=${playState.musicId}")
                                        sendInitialPlayState(webSocket, playState)
                                        stateSent = true
                                    } else {
                                        Timber.d("[WebSocket] Listener #$index returned null play state (no playback)")
                                    }
                                } catch (e: Exception) {
                                    Timber.e("[WebSocket] Listener #$index onConnected exception", e)
                                }
                            }
                            
                            if (!stateSent) {
                                Timber.w("[WebSocket] All listeners returned null play state")
                            }
                            
                            // Step 3: 最后启动心跳机制
                            if (config.enableHeartbeat) {
                                startHeartbeat()
                            }
                        } else {
                            // V1 协议：不需要握手
                            isHandshakeComplete = true
                            Timber.i("[WebSocket] Ready (V1 binary protocol)")
                            
                            // 通知监听器并获取初始状态
                            var stateSent = false
                            Timber.d("[WebSocket] Starting to traverse listeners, total: ${listeners.size}")
                            listeners.forEachIndexed { index, listener ->
                                try {
                                    Timber.d("[WebSocket] Calling listener #$index.onConnected()")
                                    listener.onConnected()
                                    
                                    val playState = listener.getCurrentPlayState()
                                    if (playState != null) {
                                        Timber.d("[WebSocket] Listener #$index returned valid play state: ${playState.musicName}")
                                        sendInitialPlayState(webSocket, playState)
                                        stateSent = true
                                    } else {
                                        Timber.d("[WebSocket] Listener #$index returned null play state (no playback)")
                                    }
                                } catch (e: Exception) {
                                    Timber.e("[WebSocket] Listener #$index onConnected exception", e)
                                }
                            }
                            
                            if (!stateSent) {
                                Timber.w("[WebSocket] All listeners returned null play state")
                            }
                            
                            if (config.enableHeartbeat) {
                                startHeartbeat()
                            }
                        }
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Timber.d("[WebSocket] Received message: $text")
                        
                        // 尝试解析为 V2 协议消息
                        try {
                            val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text)
                            val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.content
                            
                            when (type) {
                                "pong" -> {
                                    Timber.d("[WebSocket] Received pong heartbeat response")
                                    // 心跳响应，无需特殊处理
                                }
                                "command" -> {
                                    Timber.d("[PlaybackControl] Received control command")
                                    // 可以在此解析具体的命令
                                }
                                else -> {
                                    Timber.d("[WebSocket] Received other type message: $type")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w("[WebSocket] Cannot parse as V2 protocol message, may be old format", e)
                        }
                        
                        listeners.forEach { listener ->
                            try {
                                listener.onMessageReceived(text)
                            } catch (e: Exception) {
                                Timber.e("[WebSocket] 监听器 onMessageReceived 异常", e)
                            }
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Timber.e("[WebSocket] Connection failed", t)
                        isConnected = false
                        listeners.forEach { listener ->
                            try {
                                listener.onError(t)
                            } catch (e: Exception) {
                                Timber.e("[WebSocket] 监听器 onError 异常", e)
                            }
                        }
                        
                        // 指数退避重连策略（非阻塞）
                        scope.launch {
                            var retryCount = 0
                            val maxRetries = 5
                            
                            while (retryCount < maxRetries && isActive) {
                                // 指数退避：1s, 2s, 4s, 8s, 16s，最大 30s
                                val delayMs = minOf(1000L * (1 shl retryCount), 30000L)
                                Timber.d("[WebSocket] Waiting ${delayMs}ms for reconnect (attempt ${retryCount + 1}/$maxRetries)")
                                
                                delay(delayMs)
                                
                                if (serverUrl != null) {
                                    try {
                                        connect(serverUrl!!, forceReconnect = true)
                                        break // 连接成功则退出
                                    } catch (e: Exception) {
                                        retryCount++
                                        Timber.w("[WebSocket] Reconnect failed (${retryCount}/$maxRetries)", e) 
                                    }
                                } else {
                                    break
                                }
                            }
                        }
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Timber.d("[WebSocket] Connection closed: code=$code, reason=$reason")
                        isConnected = false
                        stopHeartbeat()
                        listeners.forEach { listener ->
                            try {
                                listener.onDisconnected()
                            } catch (e: Exception) {
                                Timber.e("[WebSocket] 监听器 onDisconnected 异常", e)   
                            }
                        }
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Timber.d("[WebSocket] Closing: code=$code, reason=$reason")
                        webSocket.close(code, reason)
                    }
                })
                
            } catch (e: Exception) {
                Timber.e("[WebSocket] Failed to create connection", e)  
                listeners.forEach { listener ->
                    try {
                        listener.onError(e)
                    } catch (ex: Exception) {
                        Timber.e("[WebSocket] 监听器 onError 异常", ex)
                    }
                }
            }
        }
    }
    
    /**
     * 断开 WebSocket 连接
     */
    fun disconnect() {
        Timber.d("[WebSocket] Disconnecting")
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        isConnected = false
        serverUrl = null
    }
    
    /**
     * 发送消息到服务器（文本）
     * 
     * @param message JSON 格式的消息
     */
    fun send(message: String) {
        if (!isConnected()) {
            Timber.w("[WebSocket] Not connected or handshake incomplete, cannot send message: $message")
            return
        }
        
        scope.launch {
            try {
                webSocket?.send(message)
                Timber.d("[WebSocket] Sent message: $message")
            } catch (e: Exception) {
                Timber.e("[WebSocket] Failed to send message: $message", e)
                listeners.forEach { listener ->
                    try {
                        listener.onError(e)
                    } catch (ex: Exception) {
                        Timber.e("[WebSocket] 监听器 onError 异常", ex)
                    }
                }
            }
        }
    }
    
    /**
     * 发送二进制消息到服务器
     * 
     * @param data 二进制数据
     */
    fun send(data: ByteArray) {
        if (!isConnected()) {
            Timber.w("[WebSocket] Not connected, cannot send binary message")
            return
        }
        
        scope.launch {
            try {
                val byteString = ByteString.of(*data)
                webSocket?.send(byteString)
                Timber.d("[WebSocket] Sent binary message: ${data.size} bytes")
            } catch (e: Exception) {
                Timber.e("[WebSocket] Failed to send binary message", e)
                listeners.forEach { listener ->
                    try {
                        listener.onError(e)
                    } catch (ex: Exception) {
                        Timber.e("[WebSocket] 监听器 onError 异常", ex)
                    }
                }
            }
        }
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return isConnected && webSocket != null && isHandshakeComplete
    }
    
    /**
     * 销毁客户端，释放资源
     */
    fun destroy() {
        disconnect()
        scope.cancel()
        listeners.clear()
        isHandshakeComplete = false
        stopHeartbeat()
    }
    
    /**
     * 启动心跳机制
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel() // 取消旧的心跳
        
        heartbeatJob = scope.launch {
            Timber.d("[WebSocket] Starting heartbeat mechanism, interval: ${config.heartbeatIntervalSeconds}s")
            
            while (isActive && isConnected()) {
                delay(config.heartbeatIntervalSeconds * 1000L)
                
                if (isConnected()) {
                    sendPing()
                }
            }
        }
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.d("[WebSocket] Heartbeat stopped")
    }
    
    /**
     * 发送初始播放状态到服务器
     * 用于在连接成功后立即同步当前播放信息
     */
    private fun sendInitialPlayState(webSocket: WebSocket, playState: PlayState) {
        scope.launch {
            try {
                Timber.d("[WebSocket] Sending initial play state:")
                Timber.d("[WebSocket]  - Song ID: ${playState.musicId}")
                Timber.d("[WebSocket]  - Song name: ${playState.musicName}")
                Timber.d("[WebSocket]  - Artist: ${playState.artistName}")
                Timber.d("[WebSocket]  - Duration: ${playState.duration}ms")
                Timber.d("[WebSocket]  - Progress: ${playState.progress}ms")
                Timber.d("[WebSocket]  - Playback state: ${if (playState.isPlaying) "playing" else "paused"}")
                Timber.d("[WebSocket]  - Lyrics: ${if (!playState.ttmlLyric.isNullOrBlank()) "yes" else "no"}")
                
                // 1. 发送歌曲信息
                val musicInfoMsg = WsProtocolV2Helper.createSetMusicUpdate(
                    musicId = playState.musicId,
                    musicName = playState.musicName,
                    albumName = playState.albumName,
                    artists = listOf(WsProtocolV2Helper.Artist("1", playState.artistName)),
                    duration = playState.duration
                )
                webSocket.send(musicInfoMsg)
                Timber.d("[WebSocket] Sent song info message")
                
                // 2. 发送播放进度和状态
                val progressMessage = WsProtocolV2Helper.createProgressUpdate(playState.progress)
                webSocket.send(progressMessage)
                Timber.d("[WebSocket] Sent progress update message")
                
                val stateMessage = if (playState.isPlaying) {
                    WsProtocolV2Helper.createResumedUpdate()
                } else {
                    WsProtocolV2Helper.createPausedUpdate()
                }
                webSocket.send(stateMessage)
                Timber.d("[WebSocket] Sent playback state message")
                
                // 3. 如果有歌词，发送歌词（可选失败，不影响其他状态）
                if (!playState.ttmlLyric.isNullOrBlank()) {
                    try {
                        val ttmlMessage = WsProtocolV2Helper.createTTMLLyricUpdate(playState.ttmlLyric)
                        webSocket.send(ttmlMessage)
                        Timber.d("[WebSocket] Sent lyrics message")
                    } catch (e: Exception) {
                        Timber.e("[LyricsMatcher] Failed to send initial lyrics, but not affecting other state sync", e)
                        // 不重新抛出异常，避免因为歌词错误导致整个状态同步失败
                    }
                }
                
                Timber.i("[WebSocket] ✓ Initial play state sent completed")
            } catch (e: Exception) {
                Timber.e("[WebSocket] ✗ Failed to send initial play state: ${e.message}", e)
                // 只在非歌词相关错误时才重新抛出
                throw e
            }
        }
    }
    
    /**
     * 发送 Initialize 握手消息
     * V2 协议必须在连接后发送此消息
     */
    private fun sendInitializeHandshake(webSocket: WebSocket) {
        when (negotiatedProtocolVersion) {
            WsProtocolVersion.V2 -> {
                // V2 协议：发送 JSON 格式的 Initialize 消息
                val initializeMessage = """{"type":"initialize"}"""
                webSocket.send(initializeMessage)
                Timber.d("[WebSocket] Sent V2 Initialize handshake message")
            }
            WsProtocolVersion.V1 -> {
                // V1 协议：不需要 Initialize 握手
                Timber.d("[WebSocket] V1 binary protocol: skipping Initialize handshake")
            }
        }
    }
    
    /**
     * 发送 Ping 心跳消息
     */
    fun sendPing() {
        if (!isConnected()) {
            Timber.w("[WebSocket] Not connected, cannot send ping")
            return
        }
        
        when (negotiatedProtocolVersion) {
            WsProtocolVersion.V2 -> {
                val pingMessage = """{"type":"ping"}"""
                send(pingMessage)
                Timber.d("[WebSocket] Sent ping heartbeat")
            }
            WsProtocolVersion.V1 -> {
                // V1 协议：发送二进制 Ping 消息 (Magic Number = 0)
                val pingData = byteArrayOf(0x00, 0x00)
                send(pingData)
                Timber.d("[WebSocket] Sent V1 ping heartbeat")
            }
        }
    }
    
    /**
     * 发送 Pong 响应
     */
    fun sendPong() {
        if (!isConnected()) {
            Timber.w("[WebSocket] Not connected, cannot send pong")
            return
        }
        
        when (negotiatedProtocolVersion) {
            WsProtocolVersion.V2 -> {
                val pongMessage = """{"type":"pong"}"""
                send(pongMessage)
                Timber.d("[WebSocket] Sent pong response")
            }
            WsProtocolVersion.V1 -> {
                // V1 协议：发送二进制 Pong 消息 (Magic Number = 1)
                val pongData = byteArrayOf(0x01, 0x00)
                send(pongData)
                Timber.d("[WebSocket] Sent V1 pong response")
            }
        }
    }
    
    /**
     * 获取当前协商的协议版本
     */
    fun getNegotiatedProtocol(): WsProtocolVersion {
        return negotiatedProtocolVersion
    }
}
