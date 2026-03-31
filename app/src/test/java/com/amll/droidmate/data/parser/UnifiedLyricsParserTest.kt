package com.amll.droidmate.data.parser

import org.junit.Assert.*
import org.junit.Test

class UnifiedLyricsParserTest {

    @Test
    fun `parse KRC sample should not be misdetected as QRC`() {
        val sample = """
            [ti:Sample]
            [ar:Artist]
            [kana:aa(123,456)bb]
            [0,1000]<0,500,0>a<500,500,0>b
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(sample, title = "Sample", artist = "Artist")
        assertNotNull(lyrics)
        assertEquals("Sample", lyrics?.metadata?.title)
        assertEquals("Artist", lyrics?.metadata?.artist)
        assertEquals(1, lyrics?.lines?.size)
        // make sure it didn't end up empty due to QRC parser
        assertEquals("ab", lyrics?.lines?.get(0)?.text)
    }

    @Test
    fun `parse should preserve metadata lines when processing disabled`() {
        val sample = """
            [00:00.000] 词：张三
            [00:01.000] 作曲：李四
            [00:02.000] Hello
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(
            sample,
            title = "Sample",
            artist = "Artist",
            processMetadata = false
        )
        assertNotNull(lyrics)
        val linesText = lyrics!!.lines.map { it.text }
        assertTrue(
            "Expected metadata lines to be preserved, got: $linesText",
            linesText.any { it.contains("词：") }
        )
        assertTrue(
            "Expected metadata lines to be preserved, got: $linesText",
            linesText.any { it.contains("作曲：") }
        )
    }

    @Test
    fun `parse should ignore timestamp placeholder lines in QRC`() {
        // Some QQ QRC payloads include lines like [240410,1651](240410,1651) which contain no actual lyrics.
        // These should not become visible lyric lines.
        val sample = """
            [0,1000]<0,500,0>a<500,500,0>b
            [240410,1651](240410,1651)
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(sample, title = "Sample", artist = "Artist")
        assertNotNull(lyrics)
        assertEquals(1, lyrics!!.lines.size)
        assertEquals("ab", lyrics.lines[0].text)
    }

    @Test
    fun `parse should handle QQ QRC XML output`() {
        val sample = """
            <?xml version=\"1.0\" encoding=\"utf-8\"?>
            <QrcInfos>
              <QrcHeadInfo SaveTime=\"253\" Version=\"100\"/>
              <LyricInfo LyricCount=\"1\">
                <Lyric_1 LyricType=\"1\" LyricContent=\"[ti:唯一]\n[ar:G.E.M. 邓紫棋]\n[al:T.I.M.E.]\n[offset:0]\n[205,1638]唯(205,232)一(437,105)\"/>
              </LyricInfo>
            </QrcInfos>
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(sample, title = "Sample", artist = "Artist")
        assertNotNull(lyrics)
        assertTrue("Expected some parsed lines", lyrics!!.lines.isNotEmpty())
        assertTrue("Expected lyric text to contain the character '唯'", lyrics.lines.any { it.text.contains("唯") })
    }

    @Test
    fun `parse TTML should include song parts`() {
        val sample = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <body>
                <div begin="00:00.000" end="00:05.000" itunes:songPart="Intro">
                  <p begin="00:00.000" end="00:05.000">Hello</p>
                </div>
                <div begin="00:05.000" end="00:10.000" songPart="Verse">
                  <p begin="00:05.000" end="00:10.000">World</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(sample, title = "Sample", artist = "Artist")
        assertNotNull(lyrics)
        assertEquals(2, lyrics!!.songParts.size)
        assertEquals(listOf("Intro", "Verse"), lyrics.songParts.map { it.name })
    }
}
