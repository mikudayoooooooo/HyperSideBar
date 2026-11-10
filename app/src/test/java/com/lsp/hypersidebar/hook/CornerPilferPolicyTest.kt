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

    /**
     * 2a 分档（[CornerPilferPolicy.decideSysJudge]）——锁四件事：
     * 1) 显式属性仍是排障通道，"1"/"0" 都抢在版本判据前；
     * 2) 旧属性语义"非 '0' 即开"不收紧——显式乱码（如 "oqxk" 这类手误）
     *    必须保持开，读失败只能 fallback，不能反过来改变行为；
     * 3) 8602 起未设置 ⇒ 关（2a 的 pilfer 同样会触发 request_redirect 收回流）；
     * 4) 读不到 versionCode ⇒ 保守开（旧行为），与路径①判据同一失败策略。
     */
    @Test
    fun `sysjudge explicit 1 wins`() {
        assertTrue(CornerPilferPolicy.decideSysJudge("1", 801_028_602L))
    }

    @Test
    fun `sysjudge explicit 0 wins`() {
        assertFalse(CornerPilferPolicy.decideSysJudge("0", 100L))
    }

    @Test
    fun `sysjudge garbage prop keeps legacy on`() {
        // 旧语义是 `!= "0"`：任何非 "0" 的显式值都意味着"有人主动排障"，开
        assertTrue(CornerPilferPolicy.decideSysJudge("abc", 801_028_602L))
    }

    @Test
    fun `sysjudge old launcher stays on`() {
        assertTrue(CornerPilferPolicy.decideSysJudge("", 801_028_601L))
        assertTrue(CornerPilferPolicy.decideSysJudge("", 801_027_719L))
    }

    @Test
    fun `sysjudge 8602 and later default off`() {
        assertFalse(CornerPilferPolicy.decideSysJudge("", 801_028_602L))
        assertFalse(CornerPilferPolicy.decideSysJudge("", 801_028_700L))
    }

    @Test
    fun `sysjudge unknown version keeps legacy on`() {
        assertTrue(CornerPilferPolicy.decideSysJudge("", 0L))
        assertTrue(CornerPilferPolicy.decideSysJudge("", -1L))
    }
}
