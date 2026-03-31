package com.amll.droidmate.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class TTMLSongPartParseTest {

    @Test
    fun `parseWithSongParts supports all songPart attribute aliases`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <body>
                <div begin="00:00.000" end="00:05.000" itunes:songPart="Intro">
                  <p begin="00:00.000" end="00:05.000">Intro line</p>
                </div>
                <div begin="00:05.000" end="00:10.000" itunes:song-Part="Verse">
                  <p begin="00:05.000" end="00:10.000">Verse line</p>
                </div>
                <div begin="00:10.000" end="00:15.000" songPart="PreChorus">
                  <p begin="00:10.000" end="00:15.000">Pre line</p>
                </div>
                <div begin="00:15.000" end="00:20.000" song-Part="Chorus">
                  <p begin="00:15.000" end="00:20.000">Chorus line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TTMLParser.parseWithSongParts(ttml)

        assertEquals(4, parsed.songParts.size)
        assertEquals(listOf("Intro", "Verse", "PreChorus", "Chorus"), parsed.songParts.map { it.name })
        assertEquals(4, parsed.lines.size)
    }

    @Test
    fun `parseWithSongParts falls back to paragraph range when div timing is missing`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div songPart="Verse">
                  <p begin="00:12.000" end="00:15.500">First</p>
                  <p begin="00:16.000" end="00:20.000">Second</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TTMLParser.parseWithSongParts(ttml)

        assertEquals(1, parsed.songParts.size)
        val part = parsed.songParts.first()
        assertEquals("Verse", part.name)
        assertEquals(12000L, part.startTime)
        assertEquals(20000L, part.endTime)
    }

    @Test
    fun `parseWithSongParts recovers from invalid div range using paragraph range`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div begin="00:20.000" end="00:10.000" songPart="Broken">
                  <p begin="00:21.000" end="00:24.000">Recovered timing</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TTMLParser.parseWithSongParts(ttml)

        assertEquals(1, parsed.songParts.size)
        assertEquals(20000L, parsed.songParts.first().startTime)
        assertEquals(24000L, parsed.songParts.first().endTime)
    }
}
