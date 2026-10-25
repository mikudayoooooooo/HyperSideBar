package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件式推荐排序核心单测（计划 §A1 验收）。
 * 口径：final = rom + weight×min(cnt, cap)；source null ⇒ 纯 ROM 序（降级语义）。
 */
class SuggestionRankerTest {

    private val w = 30
    private val cap = 5

    @Test
    fun `source hit boosts target above higher rom score`() {
        // b 的 ROM 分远高，但 a 有来源加权后反超（一对启动即顶前——设计意图）
        val rom = mapOf("a" to 10, "b" to 80)
        val ctx = mapOf("src" to mapOf("a" to 2))
        val out = SuggestionRanker.rank(rom, ctx, "src", w, cap)
        assertEquals(listOf("b", "a"), out) // a=10+60=70 < b=80 ⇒ 仍 b 先
        // 三次启动（+90）后反超
        val ctx2 = mapOf("src" to mapOf("a" to 3))
        assertEquals(listOf("a", "b"), SuggestionRanker.rank(rom, ctx2, "src", w, cap))
    }

    @Test
    fun `source miss equals pure rom order`() {
        val rom = mapOf("b" to 80, "a" to 10)
        val ctx = mapOf("other" to mapOf("a" to 5, "z" to 9))
        // source 不在 contextCounts ⇒ 与 weight=0 / source=null 全部一致
        val expected = SuggestionRanker.rank(rom, emptyMap(), null, w, cap)
        assertEquals(expected, SuggestionRanker.rank(rom, ctx, "missing", w, cap))
        assertEquals(expected, SuggestionRanker.rank(rom, ctx, null, w, cap))
        assertEquals(listOf("b", "a"), expected)
    }

    @Test
    fun `weight zero falls back to rom order`() {
        val rom = mapOf("b" to 80, "a" to 10)
        val ctx = mapOf("src" to mapOf("a" to 5))
        assertEquals(listOf("b", "a"), SuggestionRanker.rank(rom, ctx, "src", 0, cap))
    }

    @Test
    fun `cap truncates count`() {
        val rom = mapOf("a" to 0)
        val ctx = mapOf("src" to mapOf("a" to 100))
        // cap=5 ⇒ final = 0 + 30×5 = 150；cap=2 ⇒ 60
        assertEquals(150, finalOf(rom, ctx, "src", w, 5, "a"))
        assertEquals(60, finalOf(rom, ctx, "src", w, 2, "a"))
    }

    @Test
    fun `negative count treated as zero`() {
        val rom = mapOf("a" to 7)
        val ctx = mapOf("src" to mapOf("a" to -3))
        assertEquals(7, finalOf(rom, ctx, "src", w, cap, "a"))
    }

    @Test
    fun `union keeps context-only and rom-only zero entries`() {
        val rom = mapOf("rom.zero" to 0, "rom.high" to 50)
        val ctx = mapOf("src" to mapOf("ctx.only" to 1))
        val out = SuggestionRanker.rank(rom, ctx, "src", w, cap)
        // ctx.only=30 > rom.zero=0；rom.high 仍最高
        assertEquals(listOf("rom.high", "ctx.only", "rom.zero"), out)
    }

    @Test
    fun `tie broken by rom then pkg ascending`() {
        val rom = mapOf("bbb" to 10, "aaa" to 10, "ccc" to 20)
        // 同 final 同 rom ⇒ pkg 升序（aaa,bbb），ccc 分高在前
        assertEquals(listOf("ccc", "aaa", "bbb"), SuggestionRanker.rank(rom, emptyMap(), null, w, cap))
    }

    @Test
    fun `deterministic across thousand runs`() {
        val rom = (1..50).associate { "p${(it * 37) % 100}" to it } // 含重复值制造同分
        val ctx = mapOf("s" to (1..20).associate { "p${(it * 37) % 100}" to it % 4 })
        val first = SuggestionRanker.rank(rom, ctx, "s", w, cap)
        repeat(1000) {
            assertEquals(first, SuggestionRanker.rank(rom, ctx, "s", w, cap))
        }
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertTrue(SuggestionRanker.rank(emptyMap(), emptyMap(), null, w, cap).isEmpty())
        assertTrue(SuggestionRanker.rank(emptyMap(), mapOf("s" to mapOf("x" to 1)), "s", w, cap) == listOf("x"))
        // 脏包名（非法字符）只是普通字符串，不崩
        assertEquals(
            listOf("a..b", "z"),
            SuggestionRanker.rank(mapOf("z" to 0), mapOf("s" to mapOf("a..b" to 1)), "s", w, cap)
        )
    }

    /** 断言辅助：计算单包 final 并要求其位于列表头（final 最大者） */
    private fun finalOf(
        rom: Map<String, Int>, ctx: Map<String, Map<String, Int>>,
        src: String, weight: Int, cap: Int, pkg: String
    ): Int {
        val romLocal = rom.toMap()
        val out = SuggestionRanker.rank(romLocal, ctx, src, weight, cap)
        assertEquals(pkg, out.first())
        return (romLocal[pkg] ?: 0) + weight * (ctx[src]?.get(pkg) ?: 0).coerceIn(0, cap)
    }
}
