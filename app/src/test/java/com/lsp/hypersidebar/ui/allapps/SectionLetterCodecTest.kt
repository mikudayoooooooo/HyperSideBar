package com.lsp.hypersidebar.ui.allapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionLetterCodecTest {

    @Test
    fun roundTrip() {
        val source = mapOf("微信" to "W", "抖音" to "D", "#app" to "#")
        val decoded = SectionLetterCodec.decode(SectionLetterCodec.encode(source))
        assertEquals(source, decoded)
    }

    @Test
    fun missingOrMalformedYieldsEmpty() {
        assertTrue(SectionLetterCodec.decode(null).isEmpty())
        assertTrue(SectionLetterCodec.decode("").isEmpty())
        assertTrue(SectionLetterCodec.decode("{").isEmpty())
    }

    @Test
    fun blankValuesAreDropped() {
        val decoded = SectionLetterCodec.decode("{\"a\":\"A\",\"b\":\"\",\"c\":\" \"}")
        assertEquals(mapOf("a" to "A"), decoded)
    }
}
