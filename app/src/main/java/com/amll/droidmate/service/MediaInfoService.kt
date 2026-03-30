package com.amll.droidmate.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import com.amll.droidmate.domain.model.NowPlayingMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 媒体信息监听服务 - 获取当前播放的歌曲信息
 * 
 * 性能优化：
 * - 使用 Coroutine + Dispatchers.IO 在后台线程执行轮询
 * - 专辑图片异步处理 + 内存/磁盘缓存
 * - Flow 发射优化，仅在关键数据变化时更新
 */
class MediaInfoService(private val context: Context) {
    
    private val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic
    
    // 后台协程作用域，使用 IO 调度器
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val mediaSessionManager: MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (e: Exception) {
        Timber.e("[MediaInfoService] Failed to get MediaSessionManager", e)
        null
    }

    private val listenerComponentName = ComponentName(context, MediaListenerService::class.java)
    
    private var currentController: MediaController? = null
    
    // 专辑封面缓存，避免重复保存相同图片
    private val albumArtCache = ConcurrentHashMap<String, String>()
    
    /**
     * 启动监听
     */
    fun startListening() {
        Timber.i("[MediaInfoService] Starting media info listener")
        scheduleNextUpdate()
    }
    
    /**
     * 停止监听
     */
    fun stopListening() {
        Timber.i("[MediaInfoService] Stopping media info listener")
        serviceScope.cancel()
    }
    
    /**
     * 更新媒体信息（在 IO 线程执行）
     */
    private suspend fun updateMediaInfo() {
        if (!serviceScope.isActive) return
        
        try {
            val activeSessions = withContext(Dispatchers.IO) {
                mediaSessionManager?.getActiveSessions(listenerComponentName)
            }
            
            if (activeSessions != null && activeSessions.isNotEmpty()) {
                val controller = activeSessions[0]
                currentController = controller
                val metadata = controller.metadata
                val playbackState = controller.playbackState
                val packageName = controller.packageName
                
                if (metadata != null && playbackState != null) {
                    val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                    val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                    val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
                    val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                    val position = playbackState.position
                    val isPlaying = playbackState.state == android.media.session.PlaybackState.STATE_PLAYING
                    
                    // 检查是否为同一首歌
                    val oldMusic = _nowPlayingMusic.value
                    val isSameSong = oldMusic?.title == title && 
                                     oldMusic?.artist == artist && 
                                     oldMusic?.packageName == packageName
                    
                    // 仅在歌曲变化时才处理专辑图
                    val albumArtUri = if (!isSameSong) {
                        processAlbumArtAsync(metadata, title, artist, packageName)
                    } else {
                        oldMusic?.albumArtUri
                    }
                    
                    // 构建新的音乐对象
                    val newMusic = NowPlayingMusic(
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        currentPosition = position,
                        isPlaying = isPlaying,
                        packageName = packageName,
                        albumArtUri = albumArtUri,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    // 仅在关键数据变化时才更新 Flow
                    if (shouldUpdate(oldMusic, newMusic)) {
                        _nowPlayingMusic.value = newMusic
                        Timber.d("[MediaInfoService] Updated media info: $title - $artist (from $packageName)")
                    }
                }
            } else {
                Timber.i("[MediaInfoService] No active media sessions found")
                currentController = null
            }
        } catch (e: SecurityException) {
            Timber.e("[MediaInfoService] Permission denied to access media sessions")
            updateMediaInfoViaContentResolver()
        } catch (e: Exception) {
            Timber.e("[MediaInfoService] Error updating media info", e)
        }
    }
    
    /**
     * 判断是否需要更新 Flow
     */
    private fun shouldUpdate(old: NowPlayingMusic?, new: NowPlayingMusic): Boolean {
        if (old == null) return true

        if (old.title != new.title) return true
        if (old.artist != new.artist) return true
        if (old.isPlaying != new.isPlaying) return true
        if (old.albumArtUri != new.albumArtUri) return true
        if (old.packageName != new.packageName) return true
        if (old.duration != new.duration) return true
        if (old.album != new.album) return true

        val positionDelta = abs(new.currentPosition - old.currentPosition)
        val timestampChanged = new.timestamp != old.timestamp

        // While playing, keep emitting position/time updates so lyric progress can follow playback.
        if (new.isPlaying) {
            return positionDelta > 0L || timestampChanged
        }

        // When paused, only emit significant jumps (for seek/scrub) to avoid noisy updates.
        return positionDelta >= 1000L
    }
    
    /**
     * 通过 ContentResolver 获取媒体信息（备选方案）
     */
    private fun updateMediaInfoViaContentResolver() {
        try {
            // 注：这是一个简化的实现
            // 实际应用可能需要使用 MediaStore 或其他方式
            Timber.i("[MediaInfoService] Attempting to get media info via ContentResolver")
        } catch (e: Exception) {
            Timber.e("[MediaInfoService] Error getting media info via ContentResolver", e)
        }
    }
    
    /**
     * 异步处理专辑封面（在 IO 线程执行）
     * 
     * 优化：
     * - 内存缓存避免重复加载
     * - 限制图片尺寸（最大 512x512）减少内存占用
     * - 降低压缩质量（75%）减少 I/O
     */
    private suspend fun processAlbumArtAsync(
        metadata: android.media.MediaMetadata,
        title: String,
        artist: String,
        packageName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 生成缓存 key
            val cacheKey = "${packageName ?: "unknown"}_${title}_$artist"
            
            // 检查内存缓存
            albumArtCache[cacheKey]?.takeIf { File(it).exists() }?.let { cached ->
                Timber.d("[AlbumArtExtractor] Hit memory cache: $cached")
                return@withContext cached
            }
            
            // 获取专辑图 Bitmap
            val albumArtBitmap = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            
            if (albumArtBitmap == null) {
                Timber.i("[AlbumArtExtractor] Failed to get bitmap, trying URI")
                return@withContext metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)
            }
            
            // 保存到缓存
            val uri = saveAlbumArtToCacheOptimized(
                bitmap = albumArtBitmap,
                title = title,
                artist = artist,
                packageName = packageName,
                cacheKey = cacheKey
            )
            
            uri?.also {
                Timber.d("[AlbumArtExtractor] Album art saved to: $it")
                albumArtCache[cacheKey] = it
            }
            
            uri
        } catch (e: Exception) {
            Timber.e("[AlbumArtExtractor] Failed to process album art", e)
            null
        }
    }
    
    /**
     * 优化版的专辑图保存方法
     * - 限制尺寸
     * - 降低质量
     * - 复用文件名
     */
    private fun saveAlbumArtToCacheOptimized(
        bitmap: Bitmap,
        title: String,
        artist: String,
        packageName: String?,
        cacheKey: String
    ): String? {
        return try {
            val cacheDir = File(context.cacheDir, "album_art")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // 使用哈希值作为文件名，避免特殊字符问题
            val safeKey = cacheKey.hashCode().toUInt().toString(16)
            val file = File(cacheDir, "album_art_${safeKey}.jpg")
            
            // 如果文件已存在且大小合理，直接返回（避免重复写入）
            if (file.exists() && file.length() > 1024) {
                return "file://${file.absolutePath}"
            }
            
            // 缩放图片至最大 512x512，减少内存占用
            val scaledBitmap = resizeBitmap(bitmap, 512)
            
            FileOutputStream(file).use { out ->
                // 压缩质量从 90 降至 75，显著减少文件大小
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                scaledBitmap.recycle() // 及时释放内存
            }
            
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            Timber.e("[AlbumArtExtractor] Failed to save album art to cache", e)
            null
        }
    }
    
    /**
     * 缩放 Bitmap 到目标尺寸
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) {
            return bitmap
        }
        
        val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * 定时更新媒体信息（使用协程而非 Handler）
     */
    private fun scheduleNextUpdate() {
        serviceScope.launch {
            while (isActive) {
                updateMediaInfo()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 播放控制
     */
    fun play() {
        currentController?.transportControls?.play()
        Timber.i("[PlaybackControl] Play command sent")
    }
    
    fun pause() {
        currentController?.transportControls?.pause()
        Timber.i("[PlaybackControl] Pause command sent")
    }
    
    fun skipToNext() {
        currentController?.transportControls?.skipToNext()
        Timber.i("[PlaybackControl] Skip to next command sent")
    }
    
    fun skipToPrevious() {
        currentController?.transportControls?.skipToPrevious()
        Timber.i("[PlaybackControl] Skip to previous command sent")
    }
    
    fun seekTo(position: Long) {
        val controller = currentController
        if (controller == null) {
            Timber.e("[PlaybackControl] Seek ignored: no active MediaController, target=$position ms")
            return
        }

        val packageName = controller.packageName
        val playbackState = controller.playbackState?.state
        controller.transportControls.seekTo(position)
        Timber.i("[PlaybackControl] Seek command sent: target=$position ms, package=$packageName, playbackState=$playbackState")
    }
    
    fun fastForward() {
        currentController?.transportControls?.fastForward()
        Timber.i("[PlaybackControl] Fast forward command sent")
    }
    
    fun rewind() {
        currentController?.transportControls?.rewind()
        Timber.i("[PlaybackControl] Rewind command sent") 
    }
    
    companion object {
        private const val UPDATE_INTERVAL_MS = 500L  // 每 500ms 更新一次
    }
}
