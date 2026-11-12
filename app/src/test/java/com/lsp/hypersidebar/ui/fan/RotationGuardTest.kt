package com.lsp.hypersidebar.ui.fan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationGuardTest {

    @Test
    fun rotationZeroIsPortrait() {
        assertFalse(RotationGuard.mismatch(0, configLandscape = false))
        assertTrue(RotationGuard.mismatch(0, configLandscape = true))
    }

    @Test
    fun rotationTwoIsPortrait() {
        assertFalse(RotationGuard.mismatch(2, configLandscape = false))
        assertTrue(RotationGuard.mismatch(2, configLandscape = true))
    }

    @Test
    fun rotationOneIsLandscape() {
        assertFalse(RotationGuard.mismatch(1, configLandscape = true))
        assertTrue(RotationGuard.mismatch(1, configLandscape = false))
    }

    @Test
    fun rotationThreeIsLandscape() {
        assertFalse(RotationGuard.mismatch(3, configLandscape = true))
        assertTrue(RotationGuard.mismatch(3, configLandscape = false))
    }

    @Test
    fun mismatchesAreDetected() {
        assertTrue(RotationGuard.mismatch(1, configLandscape = false))
        assertTrue(RotationGuard.mismatch(0, configLandscape = true))
    }

    @Test
    fun unknownRotationNeverDrops() {
        assertFalse(RotationGuard.mismatch(-1, configLandscape = true))
        assertFalse(RotationGuard.mismatch(-1, configLandscape = false))
    }
}
