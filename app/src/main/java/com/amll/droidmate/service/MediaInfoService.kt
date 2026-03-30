package com.amll.droidmate.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.amll.droidmate.domain.model.NowPlayingMusic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Media info listener service that tracks currently playing media details.
 */
class MediaInfoService(private val context: Context) {

    private val _nowPlayingMusic = MutableStateFlow<NowPlayingMusic?>(null)
    val nowPlayingMusic: StateFlow<NowPlayingMusic?> = _nowPlayingMusic

    private val mediaSessionManager: MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (e: Exception) {
        Timber.e(e, "Failed to get MediaSessionManager")
        null
    }

    private val listenerComponentName = ComponentName(context, MediaListenerService::class.java)

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var currentController: MediaController? = null
    private var lastPublished: NowPlayingMusic? = null
    private var lastTrackKey: String? = null
    private var lastAlbumArtUri: String? = null

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishNowPlayingFromController(currentController, "playback-callback")
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishNowPlayingFromController(currentController, "metadata-callback")
        }

        override fun onSessionDestroyed() {
            Timber.w("Media session destroyed, waiting for next poll to attach another session")
        }
    }

    /**
     * Start listening.
     */
    fun startListening() {
        Timber.i("Starting media info listener")
        updateMediaInfo(forcePublish = true)
        scheduleNextUpdate()
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        Timber.i("Stopping media info listener")
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
        attachController(null)
    }

    /**
     * Update media info. Polling is only used to discover active session changes
     * and as a fallback if callbacks are missed.
     */
    private fun updateMediaInfo(forcePublish: Boolean = false) {
        try {
            val activeSessions = mediaSessionManager?.getActiveSessions(listenerComponentName)
            val nextController = activeSessions?.firstOrNull()
            attachController(nextController)

            if (nextController == null) {
                Timber.w("No active media sessions found")
                return
            }

            if (forcePublish) {
                publishNowPlayingFromController(nextController, "poll-forced")
            } else {
                publishNowPlayingFromController(nextController, "poll")
            }
        } catch (e: SecurityException) {
            Timber.e("Permission denied to access media sessions")
            // Fallback path.
            updateMediaInfoViaContentResolver()
        } catch (e: Exception) {
            Timber.e(e, "Error updating media info")
        }
    }

    private fun attachController(nextController: MediaController?) {
        if (currentController == nextController) return

        currentController?.let { oldController ->
            try {
                oldController.unregisterCallback(mediaControllerCallback)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unregister media controller callback")
            }
        }

        currentController = nextController

        nextController?.let { controller ->
            try {
                controller.registerCallback(mediaControllerCallback, handler)
                Timber.i("Attached media controller callbacks for ${controller.packageName}")
            } catch (e: Exception) {
                Timber.w(e, "Failed to register media controller callback")
            }
        }
    }

    private fun publishNowPlayingFromController(controller: MediaController?, source: String) {
        if (controller == null) return
        val metadata = controller.metadata ?: return
        val playbackState = controller.playbackState ?: return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val packageName = controller.packageName

        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
        val rawSpeed = playbackState.playbackSpeed.takeIf { it.isFinite() } ?: 1f
        val playbackSpeed = if (isPlaying) max(0f, rawSpeed) else 0f

        val anchorPositionMs = estimateCurrentPositionMs(
            playbackState = playbackState,
            anchorElapsedMs = elapsedRealtimeMs,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed
        )

        val albumArtUri = resolveAlbumArtUri(
            metadata = metadata,
            title = title,
            artist = artist,
            packageName = packageName
        )

        val next = NowPlayingMusic(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            currentPosition = anchorPositionMs,
            playbackSpeed = playbackSpeed,
            positionAnchorMs = anchorPositionMs,
            positionAnchorElapsedMs = elapsedRealtimeMs,
            isPlaying = isPlaying,
            packageName = packageName,
            albumArtUri = albumArtUri,
            timestamp = System.currentTimeMillis()
        )

        if (isSameSnapshot(lastPublished, next)) return
        lastPublished = next
        _nowPlayingMusic.value = next
        Timber.d("Published media snapshot($source): $title - $artist (${next.currentPosition}ms, speed=${next.playbackSpeed})")
    }

    private fun isSameSnapshot(old: NowPlayingMusic?, next: NowPlayingMusic): Boolean {
        if (old == null) return false
        return old.title == next.title &&
            old.artist == next.artist &&
            old.album == next.album &&
            old.duration == next.duration &&
            old.isPlaying == next.isPlaying &&
            old.packageName == next.packageName &&
            old.albumArtUri == next.albumArtUri &&
            old.currentPosition == next.currentPosition &&
            old.positionAnchorMs == next.positionAnchorMs &&
            old.positionAnchorElapsedMs == next.positionAnchorElapsedMs &&
            old.playbackSpeed == next.playbackSpeed
    }

    private fun estimateCurrentPositionMs(
        playbackState: PlaybackState,
        anchorElapsedMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float
    ): Long {
        val basePosition = max(0L, playbackState.position)
        val lastUpdateTime = playbackState.lastPositionUpdateTime
        if (!isPlaying || playbackSpeed <= 0f || lastUpdateTime <= 0L) {
            return basePosition
        }

        val deltaElapsed = max(0L, anchorElapsedMs - lastUpdateTime)
        val estimated = basePosition + (deltaElapsed * playbackSpeed).roundToLong()
        return max(0L, estimated)
    }

    private fun resolveAlbumArtUri(
        metadata: MediaMetadata,
        title: String,
        artist: String,
        packageName: String?
    ): String? {
        val trackKey = "${packageName ?: "unknown"}::$title::$artist"
        if (trackKey == lastTrackKey && !lastAlbumArtUri.isNullOrBlank()) {
            return lastAlbumArtUri
        }

        val albumArtUri = try {
            val albumArtBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

            if (albumArtBitmap != null) {
                saveAlbumArtToCache(
                    bitmap = albumArtBitmap,
                    title = title,
                    artist = artist,
                    packageName = packageName
                )
            } else {
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve album art")
            null
        }

        lastTrackKey = trackKey
        lastAlbumArtUri = albumArtUri
        return albumArtUri
    }

    /**
     * Fallback path.
     */
    private fun updateMediaInfoViaContentResolver() {
        try {
            Timber.i("Attempting to get media info via ContentResolver")
        } catch (e: Exception) {
            Timber.e(e, "Error getting media info via ContentResolver")
        }
    }

    /**
     * Save album art to cache and return file:// URI.
     */
    private fun saveAlbumArtToCache(
        bitmap: Bitmap,
        title: String,
        artist: String,
        packageName: String?
    ): String? {
        return try {
            val cacheDir = File(context.cacheDir, "album_art")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val safeKey = ("${packageName ?: "unknown"}_${title}_${artist}").hashCode().toUInt().toString(16)
            val file = File(cacheDir, "album_art_${safeKey}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val uri = "file://${file.absolutePath}"
            Timber.d("Saved album art to cache: $uri")
            uri
        } catch (e: Exception) {
            Timber.e(e, "Failed to save album art to cache")
            null
        }
    }

    /**
     * Poll only for active session switch / fallback.
     */
    private fun scheduleNextUpdate() {
        updateRunnable = Runnable {
            updateMediaInfo()
            scheduleNextUpdate()
        }.also { runnable ->
            handler.postDelayed(runnable, UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Playback controls.
     */
    fun play() {
        currentController?.transportControls?.play()
        Timber.i("Play command sent")
    }

    fun pause() {
        currentController?.transportControls?.pause()
        Timber.i("Pause command sent")
    }

    fun skipToNext() {
        currentController?.transportControls?.skipToNext()
        Timber.i("Skip to next command sent")
    }

    fun skipToPrevious() {
        currentController?.transportControls?.skipToPrevious()
        Timber.i("Skip to previous command sent")
    }

    fun seekTo(position: Long) {
        val controller = currentController
        if (controller == null) {
            Timber.e("Seek ignored: no active MediaController, target=$position ms")
            return
        }

        val packageName = controller.packageName
        val playbackState = controller.playbackState?.state
        controller.transportControls.seekTo(position)
        Timber.i("Seek command sent: target=$position ms, package=$packageName, playbackState=$playbackState")
    }

    fun fastForward() {
        currentController?.transportControls?.fastForward()
        Timber.i("Fast forward command sent")
    }

    fun rewind() {
        currentController?.transportControls?.rewind()
        Timber.i("Rewind command sent")
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1500L
    }
}
