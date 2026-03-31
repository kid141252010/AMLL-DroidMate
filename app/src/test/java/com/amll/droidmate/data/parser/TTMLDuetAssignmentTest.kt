package com.amll.droidmate.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TTMLDuetAssignmentTest {

    @Test
    fun `ttml parser keeps first seen agent on main side when ids are reversed`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:00.000" end="00:02.000" ttm:agent="v2">Main 1</p>
                  <p begin="00:02.000" end="00:04.000" ttm:agent="v1">Duet 1</p>
                  <p begin="00:04.000" end="00:06.000" ttm:agent="v2">Main 2</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TTMLParser.parseWithSongParts(ttml)

        assertEquals(3, parsed.lines.size)
        assertFalse(parsed.lines[0].isDuet)
        assertTrue(parsed.lines[1].isDuet)
        assertFalse(parsed.lines[2].isDuet)
    }

    @Test
    fun `ttml parser keeps dominant agent on main side even when first line is duet singer`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:00.000" end="00:02.000" ttm:agent="v2">Duet intro</p>
                  <p begin="00:02.000" end="00:04.000" ttm:agent="v1">Main 1</p>
                  <p begin="00:04.000" end="00:06.000" ttm:agent="v1">Main 2</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TTMLParser.parseWithSongParts(ttml)

        assertEquals(3, parsed.lines.size)
        assertTrue(parsed.lines[0].isDuet)
        assertFalse(parsed.lines[1].isDuet)
        assertFalse(parsed.lines[2].isDuet)
    }

    @Test
    fun `unified parser does not override ttml duet side mapping`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:00.000" end="00:02.000" ttm:agent="v2">Main 1</p>
                  <p begin="00:02.000" end="00:04.000" ttm:agent="v1">Duet 1</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = UnifiedLyricsParser.parse(ttml, title = "sample", artist = "sample")
        assertNotNull(lyrics)
        assertFalse(lyrics!!.lines[0].isDuet)
        assertTrue(lyrics.lines[1].isDuet)
    }
}
