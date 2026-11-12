package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spy 消费方门控（[SpyInputPolicy]）。锁死 2026-11-12 的取舍：
 * 1. OS2/3 上没有任何消费方时不建 monitor（issue #12 的 ANR 类直接消失）；
 * 2. OS4 恒需 monitor（strip 是唯一输入源），但不能越过模块总闸；
 * 3. strip 只有 OS4 会消费；corner 还需要角功能开关本身。
 */
class SpyInputPolicyTest {

    @Test
    fun `module off never needs a monitor`() {
        assertFalse(
            SpyInputPolicy.needed(
                moduleEnabled = false, os4 = true, cornerSwipe = true,
                cornerFallbackOwned = true, blankTapEnabled = true,
            )
        )
    }

    @Test
    fun `os3 all defaults needs no monitor`() {
        assertFalse(
            SpyInputPolicy.needed(
                moduleEnabled = true, os4 = false, cornerSwipe = false,
                cornerFallbackOwned = false, blankTapEnabled = false,
            )
        )
    }

    @Test
    fun `blank tap alone needs a monitor on os3`() {
        assertTrue(
            SpyInputPolicy.needed(
                moduleEnabled = true, os4 = false, cornerSwipe = false,
                cornerFallbackOwned = false, blankTapEnabled = true,
            )
        )
    }

    @Test
    fun `corner swipe without fallback needs no monitor on os3`() {
        assertFalse(
            SpyInputPolicy.needed(
                moduleEnabled = true, os4 = false, cornerSwipe = true,
                cornerFallbackOwned = false, blankTapEnabled = false,
            )
        )
    }

    @Test
    fun `corner fallback needs a monitor on os3`() {
        assertTrue(
            SpyInputPolicy.needed(
                moduleEnabled = true, os4 = false, cornerSwipe = true,
                cornerFallbackOwned = true, blankTapEnabled = false,
            )
        )
    }

    @Test
    fun `os4 always needs a monitor even with every feature off`() {
        assertTrue(
            SpyInputPolicy.needed(
                moduleEnabled = true, os4 = true, cornerSwipe = false,
                cornerFallbackOwned = false, blankTapEnabled = false,
            )
        )
    }

    @Test
    fun `strip is only forwarded on os4`() {
        assertFalse(SpyInputPolicy.forwardStrip(moduleEnabled = true, os4 = false))
        assertTrue(SpyInputPolicy.forwardStrip(moduleEnabled = true, os4 = true))
        assertFalse(SpyInputPolicy.forwardStrip(moduleEnabled = false, os4 = true))
    }

    @Test
    fun `corner is forwarded on os4 with the feature on`() {
        assertTrue(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = true, os4 = true, cornerSwipe = true, fallbackOwned = false,
            )
        )
        assertFalse(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = true, os4 = true, cornerSwipe = false, fallbackOwned = true,
            )
        )
    }

    @Test
    fun `corner on os3 requires the fallback owner`() {
        assertTrue(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = true, os4 = false, cornerSwipe = true, fallbackOwned = true,
            )
        )
        assertFalse(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = true, os4 = false, cornerSwipe = true, fallbackOwned = false,
            )
        )
        assertFalse(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = true, os4 = false, cornerSwipe = false, fallbackOwned = true,
            )
        )
    }

    @Test
    fun `module off blocks corner forwarding`() {
        assertFalse(
            SpyInputPolicy.forwardCorner(
                moduleEnabled = false, os4 = true, cornerSwipe = true, fallbackOwned = true,
            )
        )
    }
}
