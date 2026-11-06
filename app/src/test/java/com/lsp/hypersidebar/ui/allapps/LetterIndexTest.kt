package com.lsp.hypersidebar.ui.allapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterIndexTest {

    private val letters = listOf("A", "B", "C")

    @Test
    fun topMapsToFirst() {
        assertEquals("A", letterAt(0f, 300f, letters))
    }

    @Test
    fun middleMapsToSecond() {
        assertEquals("B", letterAt(150f, 300f, letters))
    }

    @Test
    fun bottomMapsToLast() {
        assertEquals("C", letterAt(299f, 300f, letters))
    }

    @Test
    fun beyondBottomClampsToLast() {
        assertEquals("C", letterAt(999f, 300f, letters))
    }

    @Test
    fun zeroHeightIsUnknown() {
        assertNull(letterAt(10f, 0f, letters))
    }

    @Test
    fun emptyLettersIsUnknown() {
        assertNull(letterAt(10f, 300f, emptyList()))
    }
}
