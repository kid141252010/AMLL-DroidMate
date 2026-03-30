package com.amll.droidmate.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.amll.droidmate.util.PreferenceHelper

enum class CardClickAction(val value: String) {
    DIRECT_OPEN("direct_open"),
    ASK("ask"),
    NONE("none");

    companion object {
        fun fromValue(value: String?): CardClickAction {
            return entries.firstOrNull { it.value == value } ?: ASK
        }
    }
}

enum class UpdateChannel(val value: String) {
    STABLE("stable"),
    PREVIEW("preview");

    companion object {
        fun fromValue(value: String?): UpdateChannel {
            return entries.firstOrNull { it.value == value } ?: STABLE
        }
    }
}

object AppSettings {
    private const val PREFS_NAME = "droidmate_settings"
    private const val KEY_CARD_CLICK_ACTION = "card_click_action"
    private const val KEY_LYRIC_NOTIFICATION_ENABLED = "lyric_notification_enabled"
    private const val KEY_AMLL_FONT_FAMILY = "amll_font_family"
    private const val KEY_AMLL_FONT_FILE_PATH = "amll_font_file_path"
    private const val KEY_AMLL_FONT_FILE_NAME = "amll_font_file_name"
    private const val KEY_AMLL_FONT_FILES = "amll_font_files"
    private const val KEY_AMLL_ACTIVE_FONT_ID = "amll_active_font_id"
    private const val KEY_AMLL_ENABLED_FONT_IDS = "amll_enabled_font_ids"
    private const val KEY_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
    private const val KEY_SKIP_PREVIOUS_REWINDS = "skip_previous_rewinds"
    private const val KEY_PROCESS_METADATA_ENABLED = "process_metadata_enabled"
    private const val KEY_AGENT_RECOGNIZER_ENABLED = "agent_recognizer_enabled"

    // animation settings (controls lyrics motion/animation behavior)
    private const val KEY_AMLL_ANIMATION_ENABLE_SPRING = "amll_animation_enable_spring"
    private const val KEY_AMLL_ANIMATION_ENABLE_SCALE = "amll_animation_enable_scale"
    private const val KEY_AMLL_ANIMATION_ENABLE_BLUR = "amll_animation_enable_blur"
    private const val KEY_AMLL_ANIMATION_HIDE_PASSED_LINES = "amll_animation_hide_passed_lines"
    private const val KEY_AMLL_ANIMATION_WORD_FADE_WIDTH = "amll_animation_word_fade_width"
    private const val KEY_AMLL_ANIMATION_FPS = "amll_animation_fps"

    private const val DEFAULT_AMLL_ANIMATION_ENABLE_SPRING = true
    private const val DEFAULT_AMLL_ANIMATION_ENABLE_SCALE = true
    private const val DEFAULT_AMLL_ANIMATION_ENABLE_BLUR = true
    private const val DEFAULT_AMLL_ANIMATION_HIDE_PASSED_LINES = false
    private const val DEFAULT_AMLL_ANIMATION_WORD_FADE_WIDTH = 0.5f
    private const val DEFAULT_AMLL_ANIMATION_FPS = 60

    @Volatile
    private var cachedLyricTimingOffsetsRaw: String? = null

    @Volatile
    private var cachedLyricTimingOffsets: List<LyricTimingOffset> = emptyList()

    // helper to avoid repeating getSharedPreferences
    private fun prefs(context: Context) =
        PreferenceHelper(context, PREFS_NAME)

    private const val DEFAULT_AMLL_FONT_FAMILY = "\"SF Pro Display\", \"PingFang SC\", system-ui, -apple-system, \"Segoe UI\", sans-serif"

    data class AmllFontFile(
        val id: String,
        val displayName: String,
        val absolutePath: String,
        val fontFamilyName: String
    )

    fun getDefaultAmllFontFamily(): String = DEFAULT_AMLL_FONT_FAMILY

    fun getCardClickAction(context: Context): CardClickAction {
        val value = prefs(context).getString(KEY_CARD_CLICK_ACTION, CardClickAction.ASK.value)
        return CardClickAction.fromValue(value)
    }

    fun setCardClickAction(context: Context, action: CardClickAction) {
        prefs(context).putString(KEY_CARD_CLICK_ACTION, action.value)
    }

    fun isLyricNotificationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LYRIC_NOTIFICATION_ENABLED, false)
    }

    fun setLyricNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_LYRIC_NOTIFICATION_ENABLED, enabled)
    }

    fun getAmllFontFamily(context: Context): String {
        return prefs(context).getString(KEY_AMLL_FONT_FAMILY, DEFAULT_AMLL_FONT_FAMILY)
            ?: DEFAULT_AMLL_FONT_FAMILY
    }

    fun setAmllFontFamily(context: Context, fontFamily: String) {
        prefs(context).putString(KEY_AMLL_FONT_FAMILY, fontFamily)
    }

    fun getAmllFontFilePath(context: Context): String? {
        return getActiveAmllFontFile(context)?.absolutePath
    }

    fun getAmllFontFileName(context: Context): String? {
        return getActiveAmllFontFile(context)?.displayName
    }

    fun setAmllFontFile(context: Context, absolutePath: String, displayName: String) {
        val updatedList = upsertAmllFontFile(
            context = context,
            absolutePath = absolutePath,
            displayName = displayName
        )
        val added = updatedList.firstOrNull { it.absolutePath == absolutePath }
        if (added != null) {
            setActiveAmllFontFileId(context, added.id)
        }
    }

    fun clearAmllFontFile(context: Context) {
        prefs(context).edit {
            remove(KEY_AMLL_ACTIVE_FONT_ID)
            remove(KEY_AMLL_ENABLED_FONT_IDS)
            remove(KEY_AMLL_FONT_FILE_PATH)
            remove(KEY_AMLL_FONT_FILE_NAME)
        }
    }

    fun getAmllFontFiles(context: Context): List<AmllFontFile> {
        val helper = prefs(context)
        val raw = helper.getString(KEY_AMLL_FONT_FILES, null)
        if (raw.isNullOrBlank()) {
            val legacyPath = helper.getString(KEY_AMLL_FONT_FILE_PATH, null)
            val legacyName = helper.getString(KEY_AMLL_FONT_FILE_NAME, null)
            if (!legacyPath.isNullOrBlank()) {
                val fallbackName = legacyName ?: "Imported Font"
                return listOf(
                    AmllFontFile(
                        id = stableFontId(legacyPath),
                        displayName = fallbackName,
                        absolutePath = legacyPath,
                        fontFamilyName = buildFontFamilyName(fallbackName, legacyPath)
                    )
                )
            }
            return emptyList()
        }

        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val displayName = item.optString("displayName")
                    val absolutePath = item.optString("absolutePath")
                    val fontFamilyName = item.optString("fontFamilyName")
                    if (id.isBlank() || absolutePath.isBlank()) continue
                    add(
                        AmllFontFile(
                            id = id,
                            displayName = if (displayName.isBlank()) "Imported Font" else displayName,
                            absolutePath = absolutePath,
                            fontFamilyName = if (fontFamilyName.isBlank()) {
                                buildFontFamilyName(displayName, absolutePath)
                            } else {
                                fontFamilyName
                            }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setAmllFontFiles(context: Context, files: List<AmllFontFile>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONArray().apply {
            files.forEach { file ->
                put(
                    JSONObject().apply {
                        put("id", file.id)
                        put("displayName", file.displayName)
                        put("absolutePath", file.absolutePath)
                        put("fontFamilyName", file.fontFamilyName)
                    }
                )
            }
        }
        prefs.edit()
            .putString(KEY_AMLL_FONT_FILES, json.toString())
            .apply()
    }

    fun upsertAmllFontFile(context: Context, absolutePath: String, displayName: String): List<AmllFontFile> {
        val existing = getAmllFontFiles(context).toMutableList()
        val existingIndex = existing.indexOfFirst { it.absolutePath == absolutePath }
        val next = AmllFontFile(
            id = stableFontId(absolutePath),
            displayName = displayName,
            absolutePath = absolutePath,
            fontFamilyName = buildFontFamilyName(displayName, absolutePath)
        )

        if (existingIndex >= 0) {
            existing[existingIndex] = next
        } else {
            existing.add(next)
        }

        setAmllFontFiles(context, existing)
        return existing
    }

    fun removeAmllFontFile(context: Context, fileId: String): List<AmllFontFile> {
        val remaining = getAmllFontFiles(context).filterNot { it.id == fileId }
        setAmllFontFiles(context, remaining)

        val activeId = getActiveAmllFontFileId(context)
        if (activeId == fileId) {
            setActiveAmllFontFileId(context, null)
        }

        val enabled = getEnabledAmllFontFileIds(context).filterNot { it == fileId }
        setEnabledAmllFontFileIds(context, enabled)
        return remaining
    }

    fun getActiveAmllFontFileId(context: Context): String? {
        val helper = prefs(context)
        val activeId = helper.getString(KEY_AMLL_ACTIVE_FONT_ID, null)
        if (!activeId.isNullOrBlank()) return activeId

        val legacyPath = helper.getString(KEY_AMLL_FONT_FILE_PATH, null)
        return legacyPath?.takeIf { it.isNotBlank() }?.let(::stableFontId)
    }

    fun setActiveAmllFontFileId(context: Context, fileId: String?) {
        val helper = prefs(context)
        if (fileId.isNullOrBlank()) {
            helper.remove(KEY_AMLL_ACTIVE_FONT_ID)
        } else {
            helper.putString(KEY_AMLL_ACTIVE_FONT_ID, fileId)
        }
    }

    fun getEnabledAmllFontFileIds(context: Context): List<String> {
        val helper = prefs(context)
        val raw = helper.getString(KEY_AMLL_ENABLED_FONT_IDS, null)
        if (raw.isNullOrBlank()) {
            val legacyActive = getActiveAmllFontFileId(context)
            return if (legacyActive.isNullOrBlank()) emptyList() else listOf(legacyActive)
        }

        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val id = json.optString(i)
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setEnabledAmllFontFileIds(context: Context, fileIds: List<String>) {
        val normalized = fileIds.filter { it.isNotBlank() }.distinct()
        val json = JSONArray().apply {
            normalized.forEach { put(it) }
        }
        prefs(context).putString(KEY_AMLL_ENABLED_FONT_IDS, json.toString())
    }

    fun getActiveAmllFontFile(context: Context): AmllFontFile? {
        val fonts = getAmllFontFiles(context)
        if (fonts.isEmpty()) return null

        val activeId = getActiveAmllFontFileId(context)
        return fonts.firstOrNull { it.id == activeId }
    }

    fun resetAmllFontSettings(context: Context) {
        setAmllFontFamily(context, DEFAULT_AMLL_FONT_FAMILY)
        clearAmllFontFile(context)
    }

    private fun stableFontId(absolutePath: String): String {
        return "font_" + absolutePath.hashCode().toUInt().toString(16)
    }

    private fun buildFontFamilyName(displayName: String, absolutePath: String): String {
        val base = displayName
            .substringBeforeLast('.')
            .ifBlank { absolutePath.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') }
        val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "AMLL_$safe"
    }

    fun isAutoUpdateCheckEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, true)
    }

    fun setAutoUpdateCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, enabled)
    }

    fun getUpdateChannel(context: Context): UpdateChannel {
        val value = prefs(context).getString(KEY_UPDATE_CHANNEL, UpdateChannel.STABLE.value)
        return UpdateChannel.fromValue(value)
    }

    fun setUpdateChannel(context: Context, channel: UpdateChannel) {
        prefs(context).putString(KEY_UPDATE_CHANNEL, channel.value)
    }

    fun getLastUpdateCheckAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
    }

    fun setLastUpdateCheckAt(context: Context, timestampMillis: Long) {
        prefs(context).putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMillis)
    }

    // time when user tapped “later” in update dialog; used to suppress automatic
    // checks for the next 24 hours.
    private const val KEY_LAST_UPDATE_LATER_AT = "last_update_later_at"

    fun getLastUpdateLaterAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_UPDATE_LATER_AT, 0L)
    }

    fun setLastUpdateLaterAt(context: Context, timestampMillis: Long) {
        prefs(context).putLong(KEY_LAST_UPDATE_LATER_AT, timestampMillis)
    }

    fun isSkipPreviousRewindsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SKIP_PREVIOUS_REWINDS, false)
    }

    fun setSkipPreviousRewindsEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_SKIP_PREVIOUS_REWINDS, enabled)
    }

    fun isMetadataProcessingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PROCESS_METADATA_ENABLED, false)
    }

    fun setMetadataProcessingEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_PROCESS_METADATA_ENABLED, enabled)
    }

    fun isAgentRecognizerEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AGENT_RECOGNIZER_ENABLED, false)
    }

    fun setAgentRecognizerEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AGENT_RECOGNIZER_ENABLED, enabled)
    }

    fun isAmllAnimationSpringEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AMLL_ANIMATION_ENABLE_SPRING, DEFAULT_AMLL_ANIMATION_ENABLE_SPRING)
    }

    fun setAmllAnimationSpringEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AMLL_ANIMATION_ENABLE_SPRING, enabled)
    }

    fun isAmllAnimationScaleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AMLL_ANIMATION_ENABLE_SCALE, DEFAULT_AMLL_ANIMATION_ENABLE_SCALE)
    }

    fun setAmllAnimationScaleEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AMLL_ANIMATION_ENABLE_SCALE, enabled)
    }

    fun isAmllAnimationBlurEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AMLL_ANIMATION_ENABLE_BLUR, DEFAULT_AMLL_ANIMATION_ENABLE_BLUR)
    }

    fun setAmllAnimationBlurEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AMLL_ANIMATION_ENABLE_BLUR, enabled)
    }

    fun isAmllAnimationHidePassedLinesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AMLL_ANIMATION_HIDE_PASSED_LINES, DEFAULT_AMLL_ANIMATION_HIDE_PASSED_LINES)
    }

    fun setAmllAnimationHidePassedLinesEnabled(context: Context, enabled: Boolean) {
        prefs(context).putBoolean(KEY_AMLL_ANIMATION_HIDE_PASSED_LINES, enabled)
    }

    fun getAmllAnimationWordFadeWidth(context: Context): Float {
        return prefs(context).getString(KEY_AMLL_ANIMATION_WORD_FADE_WIDTH, DEFAULT_AMLL_ANIMATION_WORD_FADE_WIDTH.toString())
            ?.toFloatOrNull() ?: DEFAULT_AMLL_ANIMATION_WORD_FADE_WIDTH
    }

    fun setAmllAnimationWordFadeWidth(context: Context, value: Float) {
        prefs(context).putString(KEY_AMLL_ANIMATION_WORD_FADE_WIDTH, value.toString())
    }

    fun getAmllAnimationFps(context: Context): Int {
        return prefs(context).getLong(KEY_AMLL_ANIMATION_FPS, DEFAULT_AMLL_ANIMATION_FPS.toLong()).toInt()
    }

    fun setAmllAnimationFps(context: Context, value: Int) {
        prefs(context).putLong(KEY_AMLL_ANIMATION_FPS, value.toLong())
    }

    // === 歌词时间轴偏移设置（基于歌曲 + 输出设备 + 音乐源） ===
    private const val KEY_LYRIC_TIMING_OFFSETS = "lyric_timing_offsets"
    private const val WILDCARD = "*"

    data class LyricTimingOffset(
        val title: String,
        val artist: String,
        val device: String,
        val source: String,
        val offsetMs: Long
    )

    fun getLyricTimingOffsets(context: Context): List<LyricTimingOffset> {
        val raw = prefs(context).getString(KEY_LYRIC_TIMING_OFFSETS, null)
        if (raw.isNullOrBlank()) return emptyList()
        if (raw == cachedLyricTimingOffsetsRaw) return cachedLyricTimingOffsets

        val parsed = try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val obj = json.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim().ifBlank { WILDCARD }
                    val artist = obj.optString("artist").trim().ifBlank { WILDCARD }
                    val device = obj.optString("device").trim().ifBlank { WILDCARD }
                    val source = obj.optString("source").trim().ifBlank { WILDCARD }
                    val offsetMs = obj.optLong("offsetMs", 0L)
                    add(LyricTimingOffset(title, artist, device, source, offsetMs))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        cachedLyricTimingOffsetsRaw = raw
        cachedLyricTimingOffsets = parsed
        return parsed
    }

    fun getLyricTimingOffset(
        context: Context,
        title: String?,
        artist: String?,
        device: String,
        source: String = WILDCARD
    ): Long? {
        // Normalize for lookup
        val normalizedTitle = title?.trim().takeIf { !it.isNullOrBlank() } ?: WILDCARD
        val normalizedArtist = artist?.trim().takeIf { !it.isNullOrBlank() } ?: WILDCARD
        val normalizedDevice = device.trim().ifBlank { WILDCARD }
        val normalizedSource = source.trim().ifBlank { WILDCARD }

        val entries = getLyricTimingOffsets(context)

        // Sum all matching entries (支持叠加)
        return entries
            .filter { entry ->
                (entry.title == WILDCARD || entry.title.equals(normalizedTitle, ignoreCase = true)) &&
                    (entry.artist == WILDCARD || entry.artist.equals(normalizedArtist, ignoreCase = true)) &&
                    (entry.device == WILDCARD || entry.device.equals(normalizedDevice, ignoreCase = true)) &&
                    (entry.source == WILDCARD || entry.source.equals(normalizedSource, ignoreCase = true))
            }
            .sumOf { it.offsetMs }
            .takeIf { it != 0L }
    }

    fun setLyricTimingOffset(
        context: Context,
        title: String,
        artist: String,
        device: String,
        offsetMs: Long,
        source: String = WILDCARD
    ) {
        val existing = getLyricTimingOffsets(context).toMutableList()
        val normalizedTitle = title.trim().ifBlank { WILDCARD }
        val normalizedArtist = artist.trim().ifBlank { WILDCARD }
        val normalizedDevice = device.trim().ifBlank { WILDCARD }
        val normalizedSource = source.trim().ifBlank { WILDCARD }
        val existingIndex = existing.indexOfFirst {
            it.title.equals(normalizedTitle, ignoreCase = true) &&
                it.artist.equals(normalizedArtist, ignoreCase = true) &&
                it.device.equals(normalizedDevice, ignoreCase = true) &&
                it.source.equals(normalizedSource, ignoreCase = true)
        }
        val entry = LyricTimingOffset(normalizedTitle, normalizedArtist, normalizedDevice, normalizedSource, offsetMs)
        if (existingIndex >= 0) {
            existing[existingIndex] = entry
        } else {
            existing.add(entry)
        }
        saveLyricTimingOffsets(context, existing)
    }

    fun removeLyricTimingOffset(
        context: Context,
        title: String,
        artist: String,
        device: String,
        source: String = WILDCARD
    ) {
        val existing = getLyricTimingOffsets(context).toMutableList()
        val normalizedTitle = title.trim().ifBlank { WILDCARD }
        val normalizedArtist = artist.trim().ifBlank { WILDCARD }
        val normalizedDevice = device.trim().ifBlank { WILDCARD }
        val normalizedSource = source.trim().ifBlank { WILDCARD }
        val remaining = existing.filterNot {
            it.title.equals(normalizedTitle, ignoreCase = true) &&
                it.artist.equals(normalizedArtist, ignoreCase = true) &&
                it.device.equals(normalizedDevice, ignoreCase = true) &&
                it.source.equals(normalizedSource, ignoreCase = true)
        }
        saveLyricTimingOffsets(context, remaining)
    }

    fun clearLyricTimingOffsets(context: Context) {
        saveLyricTimingOffsets(context, emptyList())
    }

    private fun saveLyricTimingOffsets(context: Context, entries: List<LyricTimingOffset>) {
        val json = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("title", entry.title)
                        put("artist", entry.artist)
                        put("device", entry.device)
                        put("source", entry.source)
                        put("offsetMs", entry.offsetMs)
                    }
                )
            }
        }
        val raw = json.toString()
        prefs(context).putString(KEY_LYRIC_TIMING_OFFSETS, raw)
        cachedLyricTimingOffsetsRaw = raw
        cachedLyricTimingOffsets = entries.toList()
    }
}
