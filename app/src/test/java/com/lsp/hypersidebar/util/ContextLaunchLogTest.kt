package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文启动记录纯核心单测（计划 §A3 验收：剪枝/上限/来源过滤/序列化往返）。
 */
class ContextLaunchLogTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `append and counts`() {
        var log = emptyMap<String, Map<String, List<Long>>>()
        log = ContextLaunchLog.append(log, "src", "tgt", now)
        log = ContextLaunchLog.append(log, "src", "tgt", now + 1)
        log = ContextLaunchLog.append(log, "src", "other", now + 2)
        assertEquals(2, ContextLaunchLog.counts(log, "src")["tgt"])
        assertEquals(1, ContextLaunchLog.counts(log, "src")["other"])
        assertEquals(emptyMap<String, Int>(), ContextLaunchLog.counts(log, "unknown"))
        assertEquals(emptyMap<String, Int>(), ContextLaunchLog.counts(log, null))
    }

    @Test
    fun `append rejects blank self and null source`() {
        val log = mapOf("s" to mapOf("t" to listOf(1L)))
        // 自指/空串拒绝（source==target 会在语义上变成"自己开自己"）
        assertEquals(log, ContextLaunchLog.append(log, "s", "s", now))
        assertEquals(log, ContextLaunchLog.append(log, "", "t", now))
        assertEquals(log, ContextLaunchLog.append(log, " ", "t", now))
    }

    @Test
    fun `per pair cap keeps newest ten`() {
        var log = emptyMap<String, Map<String, List<Long>>>()
        repeat(15) { log = ContextLaunchLog.append(log, "s", "t", now + it) }
        val ts = log["s"]!!["t"]!!
        assertEquals(ContextLaunchLog.PER_PAIR_CAP, ts.size)
        assertEquals(now + 5L, ts.first())
        assertEquals(now + 14L, ts.last())
    }

    @Test
    fun `prune drops at exactly window and empty pairs`() {
        val win = ContextLaunchLog.WINDOW_MS
        val log = mapOf(
            "s" to mapOf(
                "live" to listOf(now - win + 1),
                "dead" to listOf(now - win),          // 恰 7 天 ⇒ 删
                "older" to listOf(now - win - 1)
            ),
            "allDead" to mapOf("t" to listOf(now - win))
        )
        val out = ContextLaunchLog.prune(log, now)
        assertEquals(listOf(now - win + 1), out["s"]!!["live"])
        assertTrue(!out["s"]!!.containsKey("dead"))
        assertTrue(!out.containsKey("allDead"))
    }

    @Test
    fun `serialize roundtrip deterministic`() {
        var log = emptyMap<String, Map<String, List<Long>>>()
        log = ContextLaunchLog.append(log, "b", "t2", now)
        log = ContextLaunchLog.append(log, "a", "t1", now)
        log = ContextLaunchLog.append(log, "a", "t1", now - 5)
        val json = ContextLaunchLog.serialize(log)
        assertEquals(log, ContextLaunchLog.parse(json))
        // 字典序输出：同内容序列化恒等（供「没变不写」比较）
        assertEquals(json, ContextLaunchLog.serialize(ContextLaunchLog.parse(json)))
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"b\""))
    }

    @Test
    fun `parse garbage returns empty not crash`() {
        assertEquals(0, ContextLaunchLog.parse(null).size)
        assertEquals(0, ContextLaunchLog.parse("").size)
        assertEquals(0, ContextLaunchLog.parse("[1,2]").size)
        assertEquals(0, ContextLaunchLog.parse("""{"s":"notobject"}""").size)
    }

    @Test
    fun `countsAll flattens for ranker`() {
        val log = mapOf("s" to mapOf("t" to listOf(1L, 2L)))
        assertEquals(mapOf("s" to mapOf("t" to 2)), ContextLaunchLog.countsAll(log))
    }
}
