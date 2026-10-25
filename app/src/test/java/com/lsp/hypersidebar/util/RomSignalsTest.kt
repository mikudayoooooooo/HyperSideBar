package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROM 信号解析单测（计划 §A2 验收：双形态怪癖 / 7 天剪枝边界 / 落盘迁移）。
 * ROM 语义锚点（三代反编译）：`now - ts >= 7d` 即删（恰 7 天算过期）；
 * 外层 JSONArray 元素为 String（读取侧实锤），Object 形态为防御。
 */
class RomSignalsTest {

    private val now = 1_000_000_000_000L
    private val d6 = now - 6L * 24 * 3600 * 1000
    private val d7minus = now - 7L * 24 * 3600 * 1000 + 1
    private val d7 = now - 7L * 24 * 3600 * 1000          // 恰 7 天 ⇒ 过期
    private val d8 = now - 8L * 24 * 3600 * 1000

    @Test
    fun `parses ROM string-element form`() {
        // ROM 实锤形态：外层数组元素是「JSON 字符串」（字符串里才是对象）
        val inner = """{"com.a":[${d6},${d7minus}]}"""
        val json = """["${inner.replace("\"", "\\\"")}"]"""
        val out = RomSignals.parseFreeformTimestamps(json, now)
        assertEquals(listOf(d6, d7minus), out["com.a"]!!.toList())
    }

    @Test
    fun `parses object-element defensive form`() {
        val json = """[{"com.a":[${d6}]}]"""
        val out = RomSignals.parseFreeformTimestamps(json, now)
        assertEquals(listOf(d6), out["com.a"]!!.toList())
    }

    @Test
    fun `prunes at exactly 7 days and drops invalid`() {
        val json = """[{"com.a":[${d7minus},${d7},${d8},${-5}]}]"""
        val out = RomSignals.parseFreeformTimestamps(json, now)
        assertEquals(listOf(d7minus), out["com.a"]!!.toList())
    }

    @Test
    fun `empty package dropped entirely`() {
        val json = """[{"com.a":[${d8}]},{"com.b":[${d6}]}]"""
        val out = RomSignals.parseFreeformTimestamps(json, now)
        assertTrue(!out.containsKey("com.a"))
        assertEquals(listOf(d6), out["com.b"]!!.toList())
    }

    @Test
    fun `null blank garbage return empty not crash`() {
        assertEquals(0, RomSignals.parseFreeformTimestamps(null, now).size)
        assertEquals(0, RomSignals.parseFreeformTimestamps("", now).size)
        assertEquals(0, RomSignals.parseFreeformTimestamps("not json", now).size)
        assertEquals(0, RomSignals.parseFreeformTimestamps("""[{"com.a":"garbage"}]""", now).size)
    }

    @Test
    fun `returns HashMap LinkedList exact param types`() {
        // 用未过期时间戳（now 附近），避免全剪空
        val out = RomSignals.parseFreeformTimestamps("""[{"com.a":[${now - 1000}]}]""", now)
        // 反射 getMethod 匹配的形参类型必须逐字一致（HashMap/LinkedList）——
        // Kotlin HashMap() 产出 LinkedHashMap，是 HashMap 子类，反射赋值兼容
        assertTrue(java.util.HashMap::class.java.isAssignableFrom(out.javaClass))
        assertTrue(java.util.LinkedList::class.java.isAssignableFrom(out["com.a"]!!.javaClass))
    }

    // ===== 落盘 v2 / legacy =====

    @Test
    fun `scores roundtrip`() {
        val scores = mapOf("a" to 120, "b" to 0, "c" to 33)
        val json = RomSignals.serializePersistedScores(scores)
        assertEquals(scores, RomSignals.parsePersistedScores(json))
    }

    @Test
    fun `scores garbage returns null for legacy fallback`() {
        assertNull(RomSignals.parsePersistedScores(null))
        assertNull(RomSignals.parsePersistedScores("[\"legacy-array\"]"))
        assertNull(RomSignals.parsePersistedScores("junk"))
    }

    @Test
    fun `legacy array parse`() {
        assertEquals(listOf("a", "b"), RomSignals.parsePersistedLegacy("""["a","b"]"""))
        assertEquals(emptyList<String>(), RomSignals.parsePersistedLegacy("""{}"""))
        assertEquals(emptyList<String>(), RomSignals.parsePersistedLegacy(null))
    }
}
