package com.amll.droidmate.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.amll.droidmate.data.converter.TTMLConverter
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.ui.AppSettings
import com.amll.droidmate.websocket.AMLLWebSocketClient
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

@Composable
fun InitializeWebSocketListener(
    musicId: String,
    musicName: String,
    albumName: String,
    artistName: String,
    duration: Long,
    currentTime: Long,
    isPlaying: Boolean,
    lyrics: TTMLLyrics?,
    rawTtmlContent: String? = null,
    debugSource: String,
    onCommandReceived: ((String, JsonObject?) -> Unit)? = null,
    onConnectedCallback: (() -> Unit)? = null,
    onErrorCallback: ((Throwable) -> Unit)? = null
) {
    val context = LocalContext.current
    val webSocketClient = remember { AMLLWebSocketClient.getInstance() }

    LaunchedEffect(Unit) {
        if (!AppSettings.isWebSocketProtocolEnabled(context)) return@LaunchedEffect

        val wsAddress = AppSettings.getWebSocketProtocolAddress(context)
        Timber.d("[WebSocketInit] Initializing WebSocket listener: $wsAddress")

        val connectedCallback: () -> Unit = {
            onConnectedCallback?.invoke()

            if ((musicName.isNotEmpty() && musicName != "Unknown") || musicId.isNotEmpty()) {
                webSocketClient.sendMusicInfo(musicId, musicName, albumName, artistName, duration)
            } else {
                Timber.d("[WebSocketInit] Skip music info: no valid song metadata")
            }

            val ttmlContent = rawTtmlContent?.takeIf { it.isNotBlank() } ?: lyrics?.let {
                runCatching { TTMLConverter.toTTMLString(it) }
                    .getOrElse { error ->
                        Timber.e(error, "[WebSocketInit] Failed to serialize fallback TTML lyrics")
                        ""
                    }
                    .takeIf { serialized -> serialized.isNotBlank() }
            }
            if (!ttmlContent.isNullOrBlank()) {
                runCatching { webSocketClient.sendLyrics(ttmlContent) }
                    .onSuccess { Timber.d("[WebSocketInit] Sent initial lyrics") }
                    .onFailure { Timber.e(it, "[WebSocketInit] Failed to send initial lyrics") }
            }
        }

        val listener = webSocketClient.createFullFeatureListener(
            debugSource = debugSource,
            musicId = musicId,
            musicName = musicName,
            albumName = albumName,
            artistName = artistName,
            duration = duration,
            currentTime = currentTime,
            isPlaying = isPlaying,
            lyrics = lyrics,
            rawTtmlContent = rawTtmlContent,
            onConnectedCallback = connectedCallback,
            onCommandReceived = onCommandReceived,
            onErrorCallback = onErrorCallback
        )

        webSocketClient.addListener(listener)
        Timber.d("[WebSocketInit] WebSocket listener added")
    }
}
