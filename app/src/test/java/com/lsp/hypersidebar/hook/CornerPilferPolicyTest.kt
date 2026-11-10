package com.lsp.hypersidebar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角滑 pilfer 分档判据（[CornerPilferPolicy]）。
 *
 * 锁死三件事：
 * 1. 显式属性覆盖优先（排障通道不能被版本判据吃掉）；
 * 2. 8602 之前的 launcher 保持旧行为（发）——这是 2026-11-09 那次"无条件关掉"
 *    的回归防线：老版本从没因 pilfer 出过问题，不能被一台 8602 的实锤连带改掉；
 * 3. 读不到版本号时保持旧行为，避免"读失败反而改变行为"。
 */
class CornerPilferPolicyTest {

    @Test
    fun `explicit prop on wins over launcher version`() {
        assertTrue(CornerPilferPolicy.decide("1", 801_028_602L))
    }

    @Test
    fun `explicit prop off wins over old launcher`() {
        assertFalse(CornerPilferPolicy.decide("0", 100L))
    }

    @Test
    fun `old launcher keeps legacy pilfer behaviour`() {
        assertTrue(CornerPilferPolicy.decide("", 801_027_719L))
        assertTrue(CornerPilferPolicy.decide("", 1L))
    }

    @Test
    fun `8602 and later do not pilfer`() {
        assertFalse(CornerPilferPolicy.decide("", 801_028_602L))
        assertFalse(CornerPilferPolicy.decide("", 801_028_700L))
    }

    @Test
    fun `unknown launcher version keeps legacy behaviour`() {
        assertTrue(CornerPilferPolicy.decide("", 0L))
        assertTrue(CornerPilferPolicy.decide("", -1L))
    }

    @Test
    fun `blank prop is treated as unset`() {
        assertFalse(CornerPilferPolicy.decide("  ", 801_028_602L))
        assertTrue(CornerPilferPolicy.decide("  ", 801_027_719L))
    }
}
