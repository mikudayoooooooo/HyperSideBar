package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统表面闸（[SystemSurfacePolicy]）真值表。
 *
 * 安全方向是 **fail-closed**：任一系统表面标志为真（或状态未知，由调用方处理 null）都
 * 不接管/不动作；唯一例外是 `keyguardShowing && keyguardOccluded`（应用覆盖锁屏）。
 */
class SystemSurfacePolicyTest {

    private fun blocked(
        controlCenterExpanded: Boolean = false,
        panelVisible: Boolean = false,
        qsExpanded: Boolean = false,
        shadeOrQsExpanded: Boolean = false,
        keyguardShowing: Boolean = false,
        keyguardOccluded: Boolean = false,
        bouncerShowing: Boolean = false,
    ): Boolean = SystemSurfacePolicy.blocksBlankTap(
        controlCenterExpanded, panelVisible, qsExpanded, shadeOrQsExpanded,
        keyguardShowing, keyguardOccluded, bouncerShowing,
    )

    @Test
    fun `nothing on top does not block`() {
        assertFalse(blocked())
    }

    @Test
    fun `each system surface flag blocks on its own`() {
        assertTrue(blocked(controlCenterExpanded = true))
        assertTrue(blocked(panelVisible = true))
        assertTrue(blocked(qsExpanded = true))
        assertTrue(blocked(shadeOrQsExpanded = true))
        assertTrue(blocked(keyguardShowing = true))
        assertTrue(blocked(bouncerShowing = true))
    }

    @Test
    fun `keyguard over an app is not blocked`() {
        assertFalse(blocked(keyguardShowing = true, keyguardOccluded = true))
    }

    @Test
    fun `bouncer blocks even when keyguard is occluded`() {
        assertTrue(blocked(keyguardShowing = true, keyguardOccluded = true, bouncerShowing = true))
    }

    @Test
    fun `occlusion alone means nothing`() {
        assertFalse(blocked(keyguardOccluded = true))
    }

    @Test
    fun `any shade flag blocks regardless of keyguard state`() {
        assertTrue(blocked(panelVisible = true, keyguardShowing = true, keyguardOccluded = true))
        assertTrue(blocked(controlCenterExpanded = true, keyguardOccluded = true))
    }
}
