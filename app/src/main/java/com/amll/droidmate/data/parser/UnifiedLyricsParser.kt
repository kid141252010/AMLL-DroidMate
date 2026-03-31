package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.SongPart
import com.amll.droidmate.domain.model.TTMLLyrics
import com.amll.droidmate.domain.model.TTMLMetadata
import timber.log.Timber

/**
 * 统一歌词解析器
 * 
 * 根据内容自动检测格式并使用相应的解析器
 * 支持多种格式：LRC, Enhanced LRC, QRC, KRC, YRC
 * 
 * 参考: https://github.com/apoint123/Unilyric/tree/main/lyrics_helper_rs
 */
object UnifiedLyricsParser {

    private fun summarizeBgLines(lines: List<LyricLine>): String {
        val bgLines = lines.filter { it.isBG }
        val withTranslation = bgLines.count { !it.translation.isNullOrBlank() }
        val withRoman = bgLines.count { !it.transliteration.isNullOrBlank() }
        val sample = bgLines.firstOrNull()
        val sampleText = sample?.text ?: ""
        val sampleTranslation = sample?.translation ?: ""
        return "bg=${bgLines.size}, bgWithTrans=$withTranslation, bgWithRoman=$withRoman, sampleBg='${sampleText.take(40)}', sampleTrans='${sampleTranslation.take(40)}'"
    }

    private fun callerTrace(): String {
        return Throwable().stackTrace
            .drop(2)
            .take(5)
            .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }
    
    /**
     * 解析歌词内容为 TTMLLyrics 对象
     * 
     * @param content 歌词内容
     * @param title 歌曲标题（可选）
     * @param artist 艺术家（可选）
     * @param album 专辑（可选）
     * @return TTMLLyrics 对象，如果解析失败则返回 null
     */
    fun parse(
        content: String,
        title: String = "Unknown",
        artist: String = "Unknown",
        album: String? = null,
        processMetadata: Boolean = true
    ): TTMLLyrics? {
        if (content.isBlank()) {
            Timber.w("Empty lyrics content")
            return null
        }

        // Some lyric payloads (especially QQ Music) may include a leading BOM (U+FEFF), which can
        // interfere with format detection regexes. Normalize the input by trimming whitespace and
        // stripping a leading BOM before further processing.
        val normalizedContent = content.trim().trimStart('\uFEFF')
        if (normalizedContent != content) {
            Timber.d("Normalized lyrics content by stripping leading BOM/whitespace")
        }

        Timber.d("Lyrics content preview (first 300 chars): ${normalizedContent.take(300)}")
        Timber.d("[BG-LYRICS-DEBUG] UnifiedLyricsParser.parse caller trace: ${callerTrace()}")
        
        return try {
            // 检测格式（使用归一化内容来避免 BOM 等前缀影响检测）
            val format = LyricsFormat.detect(normalizedContent)
            Timber.i("Detected lyrics format: $format")

            var parsedSongParts: List<SongPart> = emptyList()
            
            // 使用相应的解析器解析
            val lines = when (format) {
                LyricsFormat.QRC -> {
                    val parsed = QrcParser.parse(normalizedContent)
                    val firstLineWords = parsed.firstOrNull()?.words?.size ?: 0
                    Timber.d("QRC parsed ${parsed.size} lines, first line word count=$firstLineWords")
                    parsed
                }
                LyricsFormat.KRC -> {
                    val parsed = KrcParser.parse(normalizedContent)
                    Timber.d("KRC parsed ${parsed.size} lines, first line words: ${parsed.firstOrNull()?.words?.size ?: 0}")
                    parsed
                }
                LyricsFormat.YRC -> {
                    val parsed = YrcParser.parse(normalizedContent)
                    Timber.d("YRC parsed ${parsed.size} lines")
                    if (parsed.isEmpty()) {
                        Timber.w("YRC parsing returned no lines, falling back to LRC parser")
                        val lrcFallback = LrcParser.parse(normalizedContent)
                        Timber.d("LRC fallback parsed ${lrcFallback.size} lines")
                        lrcFallback
                    } else {
                        parsed
                    }
                }
                LyricsFormat.ENHANCED_LRC -> {
                    val parsed = EnhancedLrcParser.parse(normalizedContent)
                    Timber.d("Enhanced LRC parsed ${parsed.size} lines")
                    parsed
                }
                LyricsFormat.LRC -> {
                    val parsed = LrcParser.parse(normalizedContent)
                    Timber.d("LRC parsed ${parsed.size} lines")
                    parsed
                }
                LyricsFormat.TTML -> {
                    // TTML 格式使用专用解析器
                    Timber.i("Parsing TTML format")
                    Timber.d("[BG-LYRICS-DEBUG] Unified TTML input has x-bg=${normalizedContent.contains("ttm:role=\"x-bg\"")}, x-translation=${normalizedContent.contains("ttm:role=\"x-translation\"")}, length=${normalizedContent.length}")
                    val parsed = TTMLParser.parseWithSongParts(normalizedContent)
                    parsedSongParts = parsed.songParts
                    Timber.d(
                        "[BG-LYRICS-DEBUG] Unified TTML parsed summary: ${summarizeBgLines(parsed.lines)}, songParts=${parsed.songParts.size}"
                    )
                    parsed.lines
                }
                LyricsFormat.PLAIN_TEXT -> {
                    // 纯文本格式转换为简单行
                    val parsed = parsePlainText(normalizedContent)
                    Timber.d("Plain text parsed ${parsed.size} lines")
                    parsed
                }
            }
            
            if (lines.isEmpty()) {
                Timber.e("No lyrics lines parsed")
                return null
            }
            
            // 抛弃可能前/后端的元数据行（例如：词：..., 作曲：...）
            val cleanedLines = if (processMetadata) {
                MetadataStripper.stripMetadataLines(lines)
            } else {
                lines
            }

            // 识别演唱者标记（A: XX），用于填充 agent/isDuet 信息
            val annotatedLines = if (processMetadata) {
                AgentRecognizer.recognizeAgents(cleanedLines)
            } else {
                cleanedLines
            }

            // 构建 TTMLLyrics 对象
            val sortedLines = annotatedLines.sortedBy { it.startTime }
            val duration = sortedLines.lastOrNull()?.endTime ?: 0L
            Timber.d("[BG-LYRICS-DEBUG] Unified final sorted summary: total=${sortedLines.size}, ${summarizeBgLines(sortedLines)}")

            TTMLLyrics(
                metadata = TTMLMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    language = detectLanguage(content),
                    duration = duration,
                    source = "DroidMate (${format.displayName})"
                ),
                lines = sortedLines,
                songParts = parsedSongParts
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse lyrics")
            null
        }
    }
    
    /**
     * 解析纯文本内容
     */
    private fun parsePlainText(content: String): List<LyricLine> {
        val lines = content.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        if (lines.isEmpty()) return emptyList()
        
        return lines.mapIndexed { index, text ->
            val startTime = index * 2000L
            LyricLine(
                startTime = startTime,
                endTime = startTime + 2000L,
                text = text,
                words = emptyList()
            )
        }
    }
    
    /**
     * 检测歌词语言
     */
    private fun detectLanguage(content: String): String {
        val hasChinese = content.any { it.code in 0x4E00..0x9FFF }
        val hasJapanese = content.any { 
            it.code in 0x3040..0x309F ||  // 平假名
            it.code in 0x30A0..0x30FF      // 片假名
        }
        val hasKorean = content.any { it.code in 0xAC00..0xD7AF }
        
        return when {
            hasJapanese -> "ja"
            hasKorean -> "ko"
            hasChinese -> "zh"
            else -> "en"
        }
    }
    
    /**
     * 解析指定格式的歌词
     * 
     * @param content 歌词内容
     * @param format 指定的格式
     * @return 歌词行列表
     */
    fun parseWithFormat(content: String, format: LyricsFormat): List<LyricLine> {
        return when (format) {
            LyricsFormat.QRC -> QrcParser.parse(content)
            LyricsFormat.KRC -> KrcParser.parse(content)
            LyricsFormat.YRC -> YrcParser.parse(content)
            LyricsFormat.ENHANCED_LRC -> EnhancedLrcParser.parse(content)
            LyricsFormat.LRC -> LrcParser.parse(content)
            LyricsFormat.PLAIN_TEXT -> parsePlainText(content)
            LyricsFormat.TTML -> TTMLParser.parse(content)
        }
    }
}
