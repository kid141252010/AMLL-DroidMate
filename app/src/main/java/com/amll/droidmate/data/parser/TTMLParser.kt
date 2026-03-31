package com.amll.droidmate.data.parser

import com.amll.droidmate.domain.model.LyricLine
import com.amll.droidmate.domain.model.LyricWord
import com.amll.droidmate.domain.model.SongPart
import timber.log.Timber
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML 格式解析器
 * 从 TTML XML 格式中提取歌词行和词级时间戳
 */
object TTMLParser {
    
    private val factory = DocumentBuilderFactory.newInstance()

    data class TTMLParseResult(
        val lines: List<LyricLine>,
        val songParts: List<SongPart>
    )

    fun parse(content: String): List<LyricLine> = parseWithSongParts(content).lines

    fun parseWithSongParts(content: String): TTMLParseResult {
        if (content.isBlank()) return TTMLParseResult(emptyList(), emptyList())

        val builder = factory.newDocumentBuilder()

        // attempt a normal parse first; if it fails we may be dealing with
        // malformed metadata tags (common in Apple-supplied TTML) and we'll
        // retry after sanitizing the input.
        fun tryParse(input: String): TTMLParseResult {
            val doc = builder.parse(input.byteInputStream())
            return parseTTMLDocument(doc)
        }

        return try {
            tryParse(content)
        } catch (e: Exception) {
            Timber.w(e, "Initial TTML parse failed, trying sanitization")
            val sanitized = sanitizeTTMLContent(content)
            return try {
                tryParse(sanitized)
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to parse TTML content after sanitization")
                TTMLParseResult(emptyList(), emptyList())
            }
        }
    }
    
    private data class ParsedParagraph(
        val mainLine: LyricLine?,
        val bgLine: LyricLine?,
        val agent: String?
    )

    private data class ParagraphParseBuffer(
        val mainWords: MutableList<LyricWord> = mutableListOf(),
        val bgWords: MutableList<LyricWord> = mutableListOf(),
        var translation: String? = null,
        var transliteration: String? = null,
        var bgTranslation: String? = null,
        var bgTransliteration: String? = null
    )

    private fun parseTTMLDocument(doc: Document): TTMLParseResult {
        val parsedParagraphs = mutableListOf<ParsedParagraph>()
        val songParts = mutableListOf<SongPart>()
        val preferredAgents = readPreferredAgents(doc)

        try {
            val body = doc.getElementsByTagName("body").item(0) as? Element
                ?: return TTMLParseResult(emptyList(), emptyList())
            songParts += parseSongParts(body)
            val paragraphs = body.getElementsByTagName("p")
            for (i in 0 until paragraphs.length) {
                val pElement = paragraphs.item(i) as? Element ?: continue
                val rawAgent = pElement.getAttribute("ttm:agent").ifBlank { pElement.getAttribute("agent") }
                if (i < 5 || i >= paragraphs.length - 2) {
                    Timber.d("[AGENT-DEBUG-RAW] Para $i: raw ttm:agent='$rawAgent'")
                }
                parseParagraph(pElement)?.let { parsedParagraphs.add(it) }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse TTML document structure")
            return TTMLParseResult(emptyList(), emptyList())
        }

        val normalizedAgents = parsedParagraphs.map { normalizeAgent(it.agent) }
        val uniqueAgents = normalizedAgents.filterNotNull().distinct()
        
        Timber.i("[AGENT] Total lines: ${parsedParagraphs.size}, unique agents: ${uniqueAgents.size}, agents: $uniqueAgents")
        
        val duetFlags = when {
            uniqueAgents.size <= 1 -> {
                Timber.d("[AGENT-DEBUG] Mode: single or no agent (all isDuet=false)")
                List(parsedParagraphs.size) { false }
            }
            uniqueAgents.size == 2 -> {
                val agentCounts = normalizedAgents
                    .filterNotNull()
                    .groupingBy { it }
                    .eachCount()
                val preferredLeftAgent = preferredAgents.firstOrNull { preferred -> uniqueAgents.any { it == preferred } }
                val leftAgent = preferredLeftAgent
                    ?: uniqueAgents.maxByOrNull { agentCounts[it] ?: 0 }
                    ?: uniqueAgents.first()
                Timber.d(
                    "[AGENT-DEBUG] Mode: exactly 2 agents. leftAgent=$leftAgent(preferred=${preferredLeftAgent ?: "none"}, count=${agentCounts[leftAgent] ?: 0}), rightAgent=${uniqueAgents.firstOrNull { it != leftAgent }}"
                )
                normalizedAgents.mapIndexed { idx, agent ->
                    val isDuet = agent != null && agent != leftAgent
                    if (idx < 5) Timber.d("[AGENT-DEBUG] Line $idx: agent=$agent -> isDuet=$isDuet")
                    isDuet
                }
            }
            else -> {
                Timber.d("[AGENT-DEBUG] Mode: >2 agents, alternating mode")
                val flags = buildAlternatingDuetFlags(normalizedAgents)
                flags.mapIndexed { idx, isDuet ->
                    if (idx < 10) Timber.d("[AGENT-DEBUG] Line $idx: agent=${normalizedAgents[idx]} -> isDuet=$isDuet")
                    isDuet
                }
            }
        }

        val lines = mutableListOf<LyricLine>()
        for ((index, parsed) in parsedParagraphs.withIndex()) {
            val isDuet = duetFlags.getOrElse(index) { false }
            parsed.mainLine?.let { lines.add(it.copy(isDuet = isDuet)) }
            parsed.bgLine?.let { lines.add(it.copy(isBG = true, isDuet = isDuet)) }
        }

        Timber.d(
            "[AGENT-DEBUG] Parse complete: ${lines.size} total output lines, ${songParts.size} song parts"
        )
        return TTMLParseResult(lines = lines, songParts = songParts)
    }

    private fun readPreferredAgents(doc: Document): List<String> {
        val agentNodes = mutableListOf<Element>()
        val explicit = doc.getElementsByTagName("ttm:agent")
        for (i in 0 until explicit.length) {
            (explicit.item(i) as? Element)?.let { agentNodes += it }
        }
        if (agentNodes.isEmpty()) {
            val fallback = doc.getElementsByTagName("agent")
            for (i in 0 until fallback.length) {
                val element = fallback.item(i) as? Element ?: continue
                if (element.getAttribute("xml:id").isBlank() && element.getAttribute("id").isBlank()) continue
                agentNodes += element
            }
        }
        if (agentNodes.isEmpty()) return emptyList()

        data class AgentMeta(val id: String, val score: Int, val order: Int)

        val parsed = agentNodes.mapIndexedNotNull { index, element ->
            val rawId = element.getAttribute("xml:id")
                .ifBlank { element.getAttribute("id") }
                .trim()
            val id = normalizeAgent(rawId) ?: return@mapIndexedNotNull null
            val type = element.getAttribute("type").trim().lowercase()
            val score = when (type) {
                "person", "main", "lead", "vocal" -> 0
                "other", "chorus", "background" -> 2
                else -> 1
            }
            AgentMeta(id = id, score = score, order = index)
        }

        return parsed
            .sortedWith(compareBy<AgentMeta> { it.score }.thenBy { it.order })
            .map { it.id }
            .distinct()
    }

    private fun parseSongParts(body: Element): List<SongPart> {
        val result = mutableListOf<SongPart>()
        val children = body.childNodes ?: return emptyList()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as? Element ?: continue
            val tag = element.tagName.substringAfter(':').lowercase()
            if (tag != "div") continue
            parseSongPartFromDiv(element)?.let { result.add(it) }
        }
        return result
    }

    private fun parseSongPartFromDiv(div: Element): SongPart? {
        val name = readSongPartAttr(div)?.trim().orEmpty()
        if (name.isEmpty()) return null

        val hasBegin = div.hasAttribute("begin")
        val hasEnd = div.hasAttribute("end")
        var startTime = if (hasBegin) timeStrToMillis(div.getAttribute("begin")) else 0L
        var endTime = if (hasEnd) timeStrToMillis(div.getAttribute("end")) else 0L

        val inferredRange = inferDivTimeRangeFromParagraphs(div)
        if (!hasBegin) {
            startTime = inferredRange?.first ?: 0L
        }
        if (!hasEnd || endTime <= startTime) {
            endTime = inferredRange?.second ?: endTime
        }

        if (endTime <= startTime) return null
        return SongPart(name = name, startTime = startTime, endTime = endTime)
    }

    private fun inferDivTimeRangeFromParagraphs(div: Element): Pair<Long, Long>? {
        val paragraphs = div.getElementsByTagName("p")
        if (paragraphs.length == 0) return null

        var minStart = Long.MAX_VALUE
        var maxEnd = Long.MIN_VALUE
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val start = timeStrToMillis(p.getAttribute("begin"))
            val endRaw = timeStrToMillis(p.getAttribute("end"))
            val end = if (endRaw > start) endRaw else start + 3000L
            minStart = minOf(minStart, start)
            maxEnd = maxOf(maxEnd, end)
        }

        if (minStart == Long.MAX_VALUE || maxEnd <= minStart) return null
        return minStart to maxEnd
    }

    private fun readSongPartAttr(element: Element): String? {
        val explicitNames = listOf("itunes:songPart", "itunes:song-Part", "songPart", "song-Part")
        for (attrName in explicitNames) {
            val value = element.getAttribute(attrName).trim()
            if (value.isNotEmpty()) return value
        }

        val attrs = element.attributes ?: return null
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) ?: continue
            val attrName = attr.nodeName?.trim()?.lowercase().orEmpty()
            val isSongPartKey = attrName == "itunes:songpart" ||
                attrName == "itunes:song-part" ||
                attrName == "songpart" ||
                attrName == "song-part"
            if (!isSongPartKey) continue
            val value = attr.nodeValue?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }

        return null
    }

    private fun parseParagraph(pElement: Element): ParsedParagraph? {
        return try {
            val beginStr = pElement.getAttribute("begin")
            val endStr = pElement.getAttribute("end")

            val startTime = timeStrToMillis(beginStr)
            var endTime = timeStrToMillis(endStr)
            if (endTime <= startTime) {
                endTime = startTime + 3000L
            }

            val agent = readAgentAttr(pElement)
            val buffer = ParagraphParseBuffer()

            parseNodeChildren(
                parent = pElement,
                inBackground = false,
                buffer = buffer
            )
            if (buffer.mainWords.isEmpty()) {
                buffer.mainWords += parseWordsByTimeAttr(pElement, inBackground = false, paragraphEnd = endTime)
            }
            if (buffer.bgWords.isEmpty()) {
                buffer.bgWords += parseWordsByTimeAttr(pElement, inBackground = true, paragraphEnd = endTime)
            }

            val mainText = if (buffer.mainWords.isNotEmpty()) {
                buffer.mainWords.joinToString(separator = "") { it.word }
            } else {
                normalizeLyricTextPreservingSpaces(collectPlainText(pElement, includeBackground = false))
            }

            val bgText = if (buffer.bgWords.isNotEmpty()) {
                buffer.bgWords.joinToString(separator = "") { it.word }
            } else {
                normalizeLyricTextPreservingSpaces(collectPlainText(pElement, includeBackground = true, backgroundOnly = true))
            }

            val mainLine = if (mainText.isNotEmpty()) {
                val mainStart = buffer.mainWords.firstOrNull()?.startTime ?: startTime
                val mainEndRaw = buffer.mainWords.lastOrNull()?.endTime ?: endTime
                val mainEnd = maxOf(mainStart, mainEndRaw)
                LyricLine(
                    startTime = mainStart,
                    endTime = mainEnd,
                    text = mainText,
                    translation = buffer.translation,
                    transliteration = buffer.transliteration,
                    words = buffer.mainWords.toList(),
                    agent = agent
                )
            } else {
                null
            }

            val bgLine = if (bgText.isNotEmpty()) {
                val bgStart = buffer.bgWords.firstOrNull()?.startTime ?: startTime
                val bgEndRaw = buffer.bgWords.lastOrNull()?.endTime ?: endTime
                val bgEnd = maxOf(bgStart, bgEndRaw)
                Timber.d("[BG-LYRICS-DEBUG] Creating BG line: text='$bgText' translation='${buffer.bgTranslation}' roman='${buffer.bgTransliteration}'")
                LyricLine(
                    startTime = bgStart,
                    endTime = bgEnd,
                    text = bgText,
                    translation = buffer.bgTranslation,
                    transliteration = buffer.bgTransliteration,
                    words = buffer.bgWords.toList(),
                    isBG = true,
                    agent = agent
                )
            } else {
                null
            }

            if (mainLine == null && bgLine == null) {
                null
            } else {
                ParsedParagraph(mainLine = mainLine, bgLine = bgLine, agent = agent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse paragraph")
            null
        }
    }

    private fun parseNodeChildren(
        parent: Node,
        inBackground: Boolean,
        buffer: ParagraphParseBuffer
    ) {
        val childNodes = parent.childNodes ?: return
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE -> {
                    // 警示后人：<p> 内 span 之间的空白是可见语义，不能直接忽略。
                    // 这里只把“无换行的纯空白分隔符”回填到前一个词尾，避免空格丢失。
                    appendDelimiterSpaceIfNeeded(child.nodeValue ?: "", inBackground, buffer)
                }

                Node.ELEMENT_NODE -> {
                    val element = child as? Element ?: continue
                    val tag = element.tagName.substringAfter(':').lowercase()
                    if (tag != "span") {
                        parseNodeChildren(element, inBackground, buffer)
                        continue
                    }

                    val role = readRoleAttr(element)
                    when (role) {
                        "x-translation" -> {
                            val text = normalizeAuxiliaryText(element.textContent ?: "")
                            if (text.isNotEmpty()) {
                                if (inBackground) {
                                    buffer.bgTranslation = text
                                    Timber.d("[BG-LYRICS-DEBUG] Collected BG translation: $text")
                                } else {
                                    // 兼容部分 TTML：x-bg 与 x-translation 平级时，将翻译归属到背景行。
                                    if (buffer.mainWords.isEmpty() && buffer.bgWords.isNotEmpty()) {
                                        buffer.bgTranslation = text
                                        Timber.d("[BG-LYRICS-DEBUG] Collected BG translation (fallback outside x-bg): $text")
                                    } else {
                                        buffer.translation = text
                                    }
                                }
                            }
                        }

                        "x-roman", "x-romanization" -> {
                            val text = normalizeAuxiliaryText(element.textContent ?: "")
                            if (text.isNotEmpty()) {
                                if (inBackground) {
                                    buffer.bgTransliteration = text
                                    Timber.d("[BG-LYRICS-DEBUG] Collected BG transliteration: $text")
                                } else {
                                    // 兼容部分 TTML：x-bg 与 x-roman 平级时，将音译归属到背景行。
                                    if (buffer.mainWords.isEmpty() && buffer.bgWords.isNotEmpty()) {
                                        buffer.bgTransliteration = text
                                        Timber.d("[BG-LYRICS-DEBUG] Collected BG transliteration (fallback outside x-bg): $text")
                                    } else {
                                        buffer.transliteration = text
                                    }
                                }
                            }
                        }

                        "x-bg" -> {
                            parseNodeChildren(element, true, buffer)

                            val bgText = normalizeLyricTextPreservingSpaces(
                                collectPlainText(element, includeBackground = true)
                            )
                            val hasBeginAttr = element.hasAttribute("begin")
                            val hasEndAttr = element.hasAttribute("end")
                            val begin = timeStrToMillis(element.getAttribute("begin"))
                            val end = timeStrToMillis(element.getAttribute("end"))
                            if (
                                bgText.isNotEmpty() &&
                                hasBeginAttr &&
                                hasEndAttr &&
                                end >= begin &&
                                !hasDirectTimedSpanChild(element)
                            ) {
                                buffer.bgWords.add(
                                    LyricWord(
                                        word = cleanBackgroundText(bgText),
                                        startTime = begin,
                                        endTime = end
                                    )
                                )
                            }
                        }

                        else -> {
                            val hasBeginAttr = element.hasAttribute("begin")
                            val hasEndAttr = element.hasAttribute("end")
                            val begin = timeStrToMillis(element.getAttribute("begin"))
                            val end = timeStrToMillis(element.getAttribute("end"))
                            if (hasBeginAttr && hasEndAttr && end >= begin && !hasDirectTimedSpanChild(element)) {
                                val text = normalizeLyricTextPreservingSpaces(element.textContent ?: "")
                                if (text.isNotEmpty()) {
                                    val word = LyricWord(
                                        word = if (inBackground) cleanBackgroundText(text) else text,
                                        startTime = begin,
                                        endTime = end
                                    )
                                    if (inBackground) {
                                        buffer.bgWords.add(word)
                                    } else {
                                        buffer.mainWords.add(word)
                                    }
                                }
                            }
                            parseNodeChildren(element, inBackground, buffer)
                        }
                    }
                }
            }
        }
    }

    private fun parseWordsByTimeAttr(
        paragraph: Element,
        inBackground: Boolean,
        paragraphEnd: Long
    ): List<LyricWord> {
        val spans = paragraph.getElementsByTagName("span")
        if (spans.length == 0) return emptyList()

        val timed = mutableListOf<Pair<Long, String>>()
        for (i in 0 until spans.length) {
            val span = spans.item(i) as? Element ?: continue
            val role = readRoleAttr(span)
            val isBgRole = role == "x-bg"
            val isAuxRole = role == "x-translation" || role == "x-roman" || role == "x-romanization"
            if (isAuxRole) continue
            if (inBackground && !isBgRole && !hasBackgroundAncestor(span)) continue
            if (!inBackground && (isBgRole || hasBackgroundAncestor(span))) continue

            val timeAttr = readTimeAttr(span)
            if (timeAttr.isBlank()) continue
            val start = timeStrToMillis(timeAttr)
            val text = normalizeLyricTextPreservingSpaces(span.textContent ?: "")
            if (text.isBlank()) continue
            val wordText = if (inBackground) cleanBackgroundText(text) else text
            timed.add(start to wordText)
        }

        if (timed.isEmpty()) return emptyList()

        return timed.mapIndexed { index, (start, text) ->
            val nextStart = timed.getOrNull(index + 1)?.first ?: paragraphEnd
            val end = if (nextStart > start) nextStart else start + 300L
            LyricWord(word = text, startTime = start, endTime = end)
        }
    }

    private fun hasBackgroundAncestor(element: Element): Boolean {
        var current: Node? = element.parentNode
        while (current != null) {
            if (current.nodeType == Node.ELEMENT_NODE) {
                val currentElement = current as Element
                if (readRoleAttr(currentElement) == "x-bg") return true
            }
            current = current.parentNode
        }
        return false
    }

    private fun readTimeAttr(element: Element): String {
        val direct = element.getAttribute("ttm:time")
            .ifBlank { element.getAttribute("time") }
            .trim()
        if (direct.isNotEmpty()) return direct

        val attrs = element.attributes ?: return ""
        for (i in 0 until attrs.length) {
            val node = attrs.item(i) ?: continue
            val name = node.nodeName?.trim()?.lowercase().orEmpty()
            if (name == "time" || name.endsWith(":time")) {
                return node.nodeValue?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun hasDirectTimedSpanChild(element: Element): Boolean {
        val children = element.childNodes ?: return false
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            val tag = child.tagName.substringAfter(':').lowercase()
            if (tag != "span") continue
            val hasBegin = child.hasAttribute("begin")
            val hasEnd = child.hasAttribute("end")
            if (hasBegin && hasEnd) return true
        }
        return false
    }

    private fun collectPlainText(
        node: Node,
        includeBackground: Boolean,
        backgroundOnly: Boolean = false,
        inBackground: Boolean = false
    ): String {
        return when (node.nodeType) {
            Node.TEXT_NODE -> {
                if (backgroundOnly && !inBackground) {
                    ""
                } else {
                    node.nodeValue ?: ""
                }
            }

            Node.ELEMENT_NODE -> {
                val element = node as Element
                val tag = element.tagName.substringAfter(':').lowercase()
                if (tag == "span") {
                    val role = readRoleAttr(element)
                    when (role) {
                        "x-translation", "x-roman", "x-romanization" -> return ""
                        "x-bg" -> {
                            if (!includeBackground) return ""
                            return iteratePlainTextChildren(
                                element,
                                includeBackground = includeBackground,
                                backgroundOnly = backgroundOnly,
                                inBackground = true
                            )
                        }
                    }
                }

                iteratePlainTextChildren(
                    element,
                    includeBackground = includeBackground,
                    backgroundOnly = backgroundOnly,
                    inBackground = inBackground
                )
            }

            else -> ""
        }
    }

    private fun iteratePlainTextChildren(
        parent: Node,
        includeBackground: Boolean,
        backgroundOnly: Boolean,
        inBackground: Boolean
    ): String {
        val builder = StringBuilder()
        val children = parent.childNodes ?: return ""
        for (i in 0 until children.length) {
            builder.append(
                collectPlainText(
                    node = children.item(i),
                    includeBackground = includeBackground,
                    backgroundOnly = backgroundOnly,
                    inBackground = inBackground
                )
            )
        }
        return builder.toString()
    }

    private fun readRoleAttr(element: Element): String {
        return (element.getAttribute("ttm:role")
            .ifBlank { element.getAttribute("role") })
            .trim()
            .lowercase()
    }

    private fun readAgentAttr(element: Element): String? {
        val raw = element.getAttribute("ttm:agent")
            .ifBlank { element.getAttribute("agent") }
            .trim()
        return raw.ifBlank { null }
    }

    private fun normalizeAgent(agent: String?): String? {
        val normalized = agent?.trim()?.lowercase()?.removePrefix("#")
        return normalized?.ifBlank { null }
    }

    private fun buildAlternatingDuetFlags(agents: List<String?>): List<Boolean> {
        val flags = MutableList(agents.size) { false }
        var lastAgent: String? = null
        var currentIsRight = false

        for (i in agents.indices) {
            val agent = agents[i]
            if (agent == null) {
                flags[i] = currentIsRight
                continue
            }

            if (lastAgent == null) {
                // 多 agent 模式下，首个声部固定在左侧。
                currentIsRight = false
                lastAgent = agent
                Timber.d("[AGENT-DEBUG] alternating: line $i first agent='$agent' -> isDuet=$currentIsRight")
            } else if (agent != lastAgent) {
                currentIsRight = !currentIsRight
                Timber.d("[AGENT-DEBUG] alternating: line $i agent change from '$lastAgent' to '$agent' -> isDuet=$currentIsRight")
                lastAgent = agent
            }

            flags[i] = currentIsRight
        }

        return flags
    }

    private fun appendDelimiterSpaceIfNeeded(
        rawDelimiter: String,
        inBackground: Boolean,
        buffer: ParagraphParseBuffer
    ) {
        if (rawDelimiter.isEmpty()) return
        if (!rawDelimiter.all { it.isWhitespace() }) return

        val containsNewline = rawDelimiter.any { it == '\n' || it == '\r' }
        if (containsNewline) return

        val target = if (inBackground) buffer.bgWords else buffer.mainWords
        if (target.isEmpty()) return

        val last = target.last()
        if (last.word.isNotEmpty() && !last.word.last().isWhitespace()) {
            target[target.lastIndex] = last.copy(word = "${last.word} ")
        }
    }

    private fun normalizeAuxiliaryText(text: String): String {
        if (text.isEmpty()) return ""

        // QQ TTML sometimes uses "//" (or repeated slashes) to mean "no translation".
        // Treat it as empty so the UI doesn't show it as literal text.
        val normalized = text.replace(Regex("[\\t\\r\\n]+"), " ").trim()
        // QQ TTML may use "//" to indicate no translation; keep a single slash.
        if (normalized == "//") return ""
        return normalized
    }

    private fun normalizeLyricTextPreservingSpaces(text: String): String {
        if (text.isEmpty()) return ""

        // 警示后人：<p>/<span> 的空格是歌词可见语义，严禁在这里 trim 或压缩空格。
        // 仅移除换行控制字符，避免格式化缩进影响，同时保留普通空格。
        return text
            .replace("\r", "")
            .replace("\n", "")
    }

    private fun cleanBackgroundText(text: String): String {
        // 背景歌词同样遵循可见空格语义：禁止 trim。
        // 仅在文本本身严格被括号包裹时去掉一层括号，不改动其它空格。
        if (text.length >= 2 && text.startsWith("(") && text.endsWith(")")) {
            return text.substring(1, text.length - 1)
        }
        return text
    }
    
    /**
     * 将 TTML 时间格式转换为毫秒
     * 格式: mm:ss.mmm (例: 00:12.345)
     */
    private fun timeStrToMillis(timeStr: String): Long {
        return try {
            if (timeStr.isBlank()) return 0L

            val normalized = timeStr.trim().lowercase().removeSuffix("s")
            if (normalized.isEmpty()) return 0L

            if (!normalized.contains(":")) {
                val seconds = normalized.toDoubleOrNull() ?: return 0L
                return (seconds * 1000.0).toLong()
            }

            val parts = normalized.split(":")
            if (parts.size !in 2..3) return 0L

            val hours: Long
            val minutes: Long
            val secondToken: String
            if (parts.size == 3) {
                hours = parts[0].toLongOrNull() ?: return 0L
                minutes = parts[1].toLongOrNull() ?: return 0L
                secondToken = parts[2]
            } else {
                hours = 0L
                minutes = parts[0].toLongOrNull() ?: return 0L
                secondToken = parts[1]
            }

            val secParts = secondToken.split(".")
            val seconds = secParts[0].toLongOrNull() ?: return 0L
            val millis = if (secParts.size > 1) {
                secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else 0L

            (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse time string: $timeStr")
            0L
        }
    }

    /**
     * Hacky pre‑parser step to strip dangerous <amll:meta> elements which often
     * contain unescaped characters or malformed attributes. Lyrics extraction
     * does not rely on them, and removing them resolves XML exceptions.
     */
    private fun sanitizeTTMLContent(raw: String): String {
        var sanitized = raw.replace(
            Regex("<amll:meta\\b[^>]*?(?:\\/>|>.*?<\\/amll:meta>)", RegexOption.DOT_MATCHES_ALL),
            ""
        )

        // Some providers double-escape XML quotes (e.g. \" in raw payloads).
        // XML parser cannot read those declarations/attributes directly.
        sanitized = sanitized
            .replace("\\\"", "\"")
            .replace("\\'", "'")

        // If prefixed ttm attributes are present but namespace is missing,
        // inject a default metadata namespace to keep parser tolerant.
        if (sanitized.contains("ttm:") && !Regex("xmlns:ttm\\s*=").containsMatchIn(sanitized)) {
            sanitized = when {
                sanitized.contains("<tt ") ->
                    sanitized.replaceFirst("<tt ", "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" ")
                sanitized.contains("<tt>") ->
                    sanitized.replaceFirst("<tt>", "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
                else -> sanitized
            }
        }

        return sanitized
    }
}
