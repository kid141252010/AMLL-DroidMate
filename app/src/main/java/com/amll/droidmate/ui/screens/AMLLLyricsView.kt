package com.amll.droidmate.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.ui.AppSettings
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * DOM: AMLL Core LyricPlayer.
 * DOM_LITE: lower-cost DOM profile.
 */
enum class AMLLRenderMode {
    DOM,
    DOM_LITE
}

data class AMLLPlaybackSnapshot(
    val positionMs: Long,
    val anchorElapsedMs: Long,
    val speed: Float,
    val isPlaying: Boolean
)

private data class AMLLBackgroundConfig(
    val fps: Int,
    val flowSpeed: Double,
    val renderScale: Double,
    val lowFreqVolume: Double
)

private data class AMLLBridgeConfig(
    val modeValue: String,
    val background: AMLLBackgroundConfig,
    val motionConfig: String,
    val fontSignature: String,
    val fontScript: String
)

private const val AMLL_LOG_TAG = "AMLL"
private val AMLL_VIEW_INSTANCE_COUNTER = AtomicInteger(0)

private fun amllDebug(message: String) {
    Timber.d(message)
}

private fun amllInfo(message: String) {
    Timber.i(message)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AMLLLyricsView(
    lyrics: TTMLLyrics?,
    playbackSnapshot: AMLLPlaybackSnapshot?,
    albumArtUri: String? = null,
    renderMode: AMLLRenderMode = AMLLRenderMode.DOM,
    debugSource: String = "unknown",
    onLyricsClick: (() -> Unit)? = null,
    onLineSeek: ((Long) -> Unit)? = null,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val instanceId = remember { AMLL_VIEW_INSTANCE_COUNTER.incrementAndGet() }
    val onLyricsClickState = rememberUpdatedState(onLyricsClick)
    val onLineSeekState = rememberUpdatedState(onLineSeek)
    val isPlayingState = rememberUpdatedState(isPlaying)

    var settingsRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsRevision += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val bridgeConfig = remember(context, settingsRevision, renderMode, debugSource) {
        loadBridgeConfig(context, renderMode, debugSource)
    }

    var isPageReady by remember { mutableStateOf(false) }
    var lastModeValue by remember { mutableStateOf<String?>(null) }
    var lastBackgroundProfileValue by remember { mutableStateOf<String?>(null) }
    var lastLyrics by remember { mutableStateOf<TTMLLyrics?>(null) }
    var lastLyricsPayload by remember { mutableStateOf<String?>(null) }
    var lastAlbumArtUri by remember { mutableStateOf<String?>(null) }
    var lastFontConfigSignature by remember { mutableStateOf<String?>(null) }
    var lastMotionConfigValue by remember { mutableStateOf<String?>(null) }
    var lastPlaybackPayload by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { androidContext ->
            amllInfo("[$debugSource#$instanceId] Creating AMLL WebView, onLineSeek=${onLineSeekState.value != null}")
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(androidContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        isPageReady = false
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        lastLyrics = null
                        lastLyricsPayload = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        lastMotionConfigValue = null
                        lastPlaybackPayload = null
                        amllDebug("[$debugSource#$instanceId] WebView page started: $url")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        isPageReady = true
                        lastModeValue = null
                        lastBackgroundProfileValue = null
                        lastAlbumArtUri = null
                        lastFontConfigSignature = null
                        lastMotionConfigValue = null
                        view.setBackgroundColor(Color.TRANSPARENT)

                        if (lastLyricsPayload != null) {
                            view.evaluateJavascript("window.updateLyrics && window.updateLyrics($lastLyricsPayload);", null)
                        }
                        if (lastPlaybackPayload != null) {
                            view.evaluateJavascript("window.syncPlayback && window.syncPlayback($lastPlaybackPayload);", null)
                        }

                        amllDebug("[$debugSource#$instanceId] WebView page finished: $url")
                        view.evaluateJavascript(
                            "window.logFromKotlin && window.logFromKotlin('[KOTLIN] page finished for $debugSource#$instanceId');",
                            null
                        )
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        amllDebug(
                            "[$debugSource#$instanceId] JS Console(${consoleMessage.messageLevel()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}): ${consoleMessage.message()}"
                        )
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                @Suppress("DEPRECATION")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                }

                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_NONE, null)

                val webViewRef = this
                addJavascriptInterface(
                    AMLLInterface(
                        debugSource = debugSource,
                        instanceId = instanceId,
                        onLineSeek = onLineSeekState.value,
                        onSeekRequested = { seekTime ->
                            webViewRef.post {
                                webViewRef.evaluateJavascript(
                                    "window.callPlayer && window.callPlayer('setIsSeeking', true);",
                                    null
                                )
                                webViewRef.evaluateJavascript(
                                    "window.updateTime && window.updateTime($seekTime);",
                                    null
                                )
                            }
                        },
                        isPlayingProvider = { isPlayingState.value }
                    ),
                    "Android"
                )
                amllDebug("[$debugSource#$instanceId] JavascriptInterface added as Android")

                setOnClickListener {
                    amllDebug("[$debugSource#$instanceId] WebView onClick listener fired")
                    onLyricsClickState.value?.invoke()
                }

                loadUrl("file:///android_asset/amll/index.html")
                amllDebug("[$debugSource#$instanceId] WebView initialized with URL: file:///android_asset/amll/index.html")
            }
        },
        update = { view ->
            if (!isPageReady) {
                amllDebug("[$debugSource#$instanceId] Bridge skipped: page not ready")
                return@AndroidView
            }

            val playbackPayload = buildPlaybackSyncPayload(playbackSnapshot, isPlaying)
            if (lastPlaybackPayload != playbackPayload) {
                view.evaluateJavascript("window.syncPlayback && window.syncPlayback($playbackPayload);", null)
                lastPlaybackPayload = playbackPayload
            }

            if (lastModeValue != bridgeConfig.modeValue) {
                amllDebug("[$debugSource#$instanceId] Bridge call: setRenderMode(${bridgeConfig.modeValue})")
                view.evaluateJavascript("window.setRenderMode && window.setRenderMode('${bridgeConfig.modeValue}');", null)
                lastModeValue = bridgeConfig.modeValue
            }

            val hasLyric = !lyrics?.lines.isNullOrEmpty()
            val backgroundProfile = buildBackgroundProfileJson(
                background = bridgeConfig.background,
                hasLyric = hasLyric,
                isPlaying = isPlaying
            )
            if (lastBackgroundProfileValue != backgroundProfile) {
                amllDebug("[$debugSource#$instanceId] Bridge call: configureBackgroundEffect(profile=$backgroundProfile)")
                view.evaluateJavascript(
                    "window.configureBackgroundEffect && window.configureBackgroundEffect($backgroundProfile);",
                    null
                )
                lastBackgroundProfileValue = backgroundProfile
            }

            if (lastMotionConfigValue != bridgeConfig.motionConfig) {
                amllDebug("[$debugSource#$instanceId] Bridge call: configureLyricMotion(profile=${bridgeConfig.motionConfig})")
                view.evaluateJavascript("window.configureLyricMotion && window.configureLyricMotion(${bridgeConfig.motionConfig});", null)
                lastMotionConfigValue = bridgeConfig.motionConfig
            }

            if (lyrics !== lastLyrics) {
                val nextPayload = if (lyrics != null) {
                    buildLyricsJson(lyrics)
                } else {
                    emptyLyricsPayload()
                }
                amllDebug(
                    "[$debugSource#$instanceId] Bridge call: updateLyrics(lines=${lyrics?.lines?.size ?: 0})"
                )
                view.evaluateJavascript("window.updateLyrics && window.updateLyrics($nextPayload);", null)
                lastLyricsPayload = nextPayload
                lastLyrics = lyrics
            }

            if (lastAlbumArtUri != albumArtUri) {
                val escapedAlbumUri = escapeJsString(albumArtUri ?: "")
                amllDebug(
                    "[$debugSource#$instanceId] Bridge call: updateAlbumArt(uri=${if (albumArtUri.isNullOrBlank()) "empty" else "present"})"
                )
                view.evaluateJavascript("window.updateAlbumArt && window.updateAlbumArt(\"$escapedAlbumUri\");", null)
                lastAlbumArtUri = albumArtUri
            }

            if (lastFontConfigSignature != bridgeConfig.fontSignature) {
                amllDebug("[$debugSource#$instanceId] Bridge call: applyFontSettings(signature=${bridgeConfig.fontSignature})")
                view.evaluateJavascript(bridgeConfig.fontScript, null)
                lastFontConfigSignature = bridgeConfig.fontSignature
            }
        },
        onRelease = { view ->
            amllInfo("[$debugSource#$instanceId] Destroying AMLL WebView")
            view.stopLoading()
            view.clearHistory()
            view.removeJavascriptInterface("Android")
            view.destroy()
        }
    )
}

private fun loadBridgeConfig(
    context: android.content.Context,
    renderMode: AMLLRenderMode,
    debugSource: String
): AMLLBridgeConfig {
    val modeValue = if (renderMode == AMLLRenderMode.DOM) "dom" else "dom-lite"
    val configuredFps = AppSettings.getAmllAnimationFps(context).coerceIn(15, 60)
    val fpsCap = if (debugSource.contains("fullscreen", ignoreCase = true)) 45 else 30
    val fpsValue = configuredFps.coerceAtMost(fpsCap)

    val background = if (renderMode == AMLLRenderMode.DOM_LITE) {
        AMLLBackgroundConfig(
            fps = fpsValue,
            flowSpeed = 1.3,
            renderScale = if (fpsCap <= 30) 0.62 else 0.68,
            lowFreqVolume = 0.9
        )
    } else {
        AMLLBackgroundConfig(
            fps = fpsValue,
            flowSpeed = 2.0,
            renderScale = if (fpsCap <= 30) 0.72 else 0.82,
            lowFreqVolume = 1.0
        )
    }

    val motionConfig = """{
        "enableSpring":${AppSettings.isAmllAnimationSpringEnabled(context)},
        "enableScale":${AppSettings.isAmllAnimationScaleEnabled(context)},
        "enableBlur":${AppSettings.isAmllAnimationBlurEnabled(context)},
        "hidePassedLines":${AppSettings.isAmllAnimationHidePassedLinesEnabled(context)},
        "wordFadeWidth":${AppSettings.getAmllAnimationWordFadeWidth(context)}
    }""".trimIndent().replace("\n", "")

    val configuredFontFamily = AppSettings.getAmllFontFamily(context)
    val fontFiles = AppSettings.getAmllFontFiles(context)
        .filter { it.absolutePath.isNotBlank() }
        .mapNotNull { item ->
            val file = File(item.absolutePath)
            if (!file.exists()) return@mapNotNull null
            FontWebEntry(
                id = item.id,
                sortKey = item.fontFamilyName,
                familyName = buildRuntimeFontFamilyName(item.fontFamilyName, item.id),
                uri = file.toURI().toString()
            )
        }

    val enabledIds = AppSettings.getEnabledAmllFontFileIds(context)
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
        append(fontFiles.joinToString(";") { "${it.id}:${it.familyName}:${it.uri}" })
        append("|")
        append(enabledFamilies.joinToString(","))
    }

    return AMLLBridgeConfig(
        modeValue = modeValue,
        background = background,
        motionConfig = motionConfig,
        fontSignature = fontSignature,
        fontScript = buildApplyFontScript(effectiveFamily, fontFiles)
    )
}

private fun buildBackgroundProfileJson(
    background: AMLLBackgroundConfig,
    hasLyric: Boolean,
    isPlaying: Boolean
): String {
    val staticMode = !hasLyric || !isPlaying
    return """{"renderer":"pixi","fps":${background.fps},"flowSpeed":${background.flowSpeed},"renderScale":${background.renderScale},"staticMode":$staticMode,"lowFreqVolume":${background.lowFreqVolume},"hasLyric":$hasLyric}"""
}

private fun buildPlaybackSyncPayload(
    playbackSnapshot: AMLLPlaybackSnapshot?,
    isPlayingFallback: Boolean
): String {
    val isPlaying = playbackSnapshot?.isPlaying ?: isPlayingFallback
    val speed = if (isPlaying) {
        playbackSnapshot?.speed?.takeIf { it.isFinite() } ?: 1f
    } else {
        0f
    }
    val clampedSpeed = speed.coerceAtLeast(0f)
    val positionMs = (playbackSnapshot?.positionMs ?: 0L).coerceAtLeast(0L)
    val anchorElapsedMs = (playbackSnapshot?.anchorElapsedMs ?: 0L).coerceAtLeast(0L)
    return """{"positionMs":$positionMs,"anchorElapsedMs":$anchorElapsedMs,"speed":$clampedSpeed,"isPlaying":$isPlaying}"""
}

private fun emptyLyricsPayload(): String {
    return """{"metadata":{"title":"","artist":""},"lines":[]}"""
}

private data class FontWebEntry(
    val id: String,
    val sortKey: String,
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

private fun buildApplyFontScript(effectiveFamily: String, files: List<FontWebEntry>): String {
    val escapedFamily = escapeJsString(effectiveFamily)
    val filesJs = files.joinToString(",") {
        "{id:\"${escapeJsString(it.id)}\",familyName:\"${escapeJsString(it.familyName)}\",uri:\"${escapeJsString(it.uri)}\"}"
    }

    return """
        (function() {
            var effectiveFamily = "$escapedFamily";
            var files = [$filesJs];
            var styleId = 'amll-dynamic-font-face-style';
            var styleNode = document.getElementById(styleId);
            if (!styleNode) {
                styleNode = document.createElement('style');
                styleNode.id = styleId;
                document.head.appendChild(styleNode);
            }

            var css = '';
            for (var i = 0; i < files.length; i += 1) {
                var item = files[i];
                if (!item || !item.familyName || !item.uri) continue;
                css += '@font-face{font-family:"' + item.familyName + '";src:url("' + item.uri + '");font-display:swap;}';
            }
            styleNode.textContent = css;

            document.documentElement.style.setProperty('--amll-user-font-family', effectiveFamily);
            document.documentElement.style.setProperty('--amll-lp-font-family', 'var(--amll-user-font-family)');

            var players = document.querySelectorAll('.amll-lyric-player');
            for (var j = 0; j < players.length; j += 1) {
                players[j].style.fontFamily = 'var(--amll-lp-font-family)';
            }
        })();
    """.trimIndent().replace("\n", " ")
}

private fun escapeJsString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
}

private fun buildLyricsJson(lyrics: TTMLLyrics): String {
    val bgLines = lyrics.lines.filter { it.isBG }
    val bgWithTranslation = bgLines.count { !it.translation.isNullOrBlank() }
    val bgWithRoman = bgLines.count { !it.transliteration.isNullOrBlank() }
    val sampleBg = bgLines.firstOrNull()
    amllDebug("[BG-LYRICS-DEBUG] buildLyricsJson summary: total=${lyrics.lines.size}, bg=${bgLines.size}, bgWithTrans=$bgWithTranslation, bgWithRoman=$bgWithRoman, sampleBg='${sampleBg?.text ?: ""}', sampleTrans='${sampleBg?.translation ?: ""}'")

    val linesJson = lyrics.lines.joinToString(",") { line ->
        val text = line.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val translation = line.translation?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        val transliteration = line.transliteration?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""

        val wordsJson = if (line.words.isNotEmpty()) {
            line.words.joinToString(",") { word ->
                val wordText = word.word.replace("\\", "\\\\").replace("\"", "\\\"")
                """{"word":"$wordText","startTime":${word.startTime},"endTime":${word.endTime}}"""
            }
        } else {
            val wordText = line.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            """{"word":"$wordText","startTime":${line.startTime},"endTime":${line.endTime}}"""
        }

        if (line.words.isNotEmpty()) {
            amllDebug("Building JSON for line: '${line.text}' with ${line.words.size} words")
        }
        if (line.isBG) {
            amllDebug("[BG-LYRICS-DEBUG] JSON for BG line: text='$text' translation='$translation' roman='$transliteration' isBG=${line.isBG}")
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
    private val onSeekRequested: ((Long) -> Unit)? = null,
    private val isPlayingProvider: () -> Boolean = { true }
) {
    @JavascriptInterface
    fun log(message: String) {
        amllDebug("[$debugSource#$instanceId] JS: $message")
    }

    @JavascriptInterface
    fun onLineClick(lineIndex: Int, startTime: Long) {
        amllInfo("[$debugSource#$instanceId] User clicked lyric line: index=$lineIndex, startTime=$startTime, callbackPresent=${onLineSeek != null}")
        onSeekRequested?.invoke(startTime)
        onLineSeek?.invoke(startTime)
    }

    @JavascriptInterface
    fun isPlaying(): Boolean {
        return isPlayingProvider()
    }
}
