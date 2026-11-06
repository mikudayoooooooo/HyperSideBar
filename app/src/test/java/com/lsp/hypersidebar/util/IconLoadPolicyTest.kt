package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IconLoadPolicyTest {

    @Test
    fun capsAtFirstScreenMax() {
        val all = (1..219).map { "p$it" }
        val first = IconLoadPolicy.firstScreen(all)
        assertEquals(IconLoadPolicy.FIRST_SCREEN_MAX, first.size)
        assertEquals(all.take(IconLoadPolicy.FIRST_SCREEN_MAX), first)
    }

    @Test
    fun dedupesAndSkipsBlank() {
        assertEquals(
            listOf("a", "b"),
            IconLoadPolicy.firstScreen(listOf("a", "", "a", "b"), max = 10)
        )
    }

    @Test
    fun zeroMaxYieldsEmpty() {
        assertEquals(emptyList<String>(), IconLoadPolicy.firstScreen(listOf("a"), max = 0))
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals(emptyList<String>(), IconLoadPolicy.firstScreen(emptyList()))
    }
}
