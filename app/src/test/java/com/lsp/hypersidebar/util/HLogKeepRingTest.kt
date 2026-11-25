package com.lsp.hypersidebar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLog 关键行保护环（2026-11-23，issue #13 日志冲刷治理）纯 JVM 测试。
 *
 * 为什么能纯 JVM：record() 的环逻辑只依赖 Entry/ArrayDeque；i()/w()/e() 会落
 * android.util.Log（unit test 抛 "not mocked"）⇒ 测试走 record 的行为等价面——
 * 直接验证 [HLog.snapshot] 的双环合并语义与 [HLog.keepDropped] 计数，
 * 用 i() 的调用方约定（关键 tag I 行 + E/W 双写）作为输入构造。
 */
class HLogKeepRingTest {

    @Test
    fun 关键tag的I行进保护环_普通I行被主环滚动() {
        // SysInputSpy 在 KEEP_TAGS 白名单：gesture end 摘要行必须留底。
        // 注意：关键行双写两环 ⇒ 主环 4000 条里也会有一部分关键行，所以噪声行
        // "不是全部消失"，而是"被挤出超过写入量-主环容量"的数量。
        repeat(HLogKeepRingProbe.CAPACITY + 50) { HLog.i("BlankTapProbe", "noise $it") }
        repeat(HLogKeepRingProbe.KEEP_CAPACITY + 50) { HLog.i("SysInputSpy", "gesture end zone=band $it") }

        val snap = HLog.snapshot()
        val noise = snap.filter { it.tag == "BlankTapProbe" }
        val kept = snap.filter { it.tag == "SysInputSpy" }
        // 主环 4000 条被关键行占掉 350 ⇒ 噪声最多剩 3650（写入 4050 ⇒ 至少 400 条被挤出主环）
        assertTrue("噪声行应被主环滚掉（got ${noise.size}）", noise.size <= HLogKeepRingProbe.CAPACITY - (HLogKeepRingProbe.KEEP_CAPACITY + 50))
        // 关键 tag I 行：保护环兜底 ⇒ 全部存活（无一条丢失）
        assertEquals("关键 tag I 行应全量保留", HLogKeepRingProbe.KEEP_CAPACITY + 50, kept.size)
    }

    @Test
    fun E和W级别恒进保护环() {
        repeat(HLogKeepRingProbe.KEEP_CAPACITY + 10) { HLog.e("AnyTag", "err $it") }
        val kept = HLog.snapshot().filter { it.level == 'E' && it.tag == "AnyTag" }
        assertTrue("E 行应被保护环保留（got ${kept.size}）", kept.size >= HLogKeepRingProbe.KEEP_CAPACITY)
    }

    @Test
    fun 保护环溢出计数递增() {
        val before = HLog.keepDropped()
        repeat(HLogKeepRingProbe.KEEP_CAPACITY + 25) { HLog.w("KeepProbe", "w $it") }
        assertTrue("溢出应计入 keepDropped", HLog.keepDropped() > before)
    }

    @Test
    fun 快照合并去重且时间升序() {
        HLog.i("TurboLayout", "keep-tag line")
        HLog.e("Other", "e line")
        val snap = HLog.snapshot()
        // 同一条 Entry（关键 tag I 行）只出现一次：主环与保护环合并时按引用去重
        assertEquals(
            "关键 tag 行不应双写两份",
            1,
            snap.count { it.tag == "TurboLayout" && it.msg == "keep-tag line" }
        )
        // 合并后按时间升序（主环 + 保护环补差重排）
        val ts = snap.map { it.wallMs }
        assertEquals("快照应按时间升序", ts, ts.sorted())
    }
}

/** 测试探针：暴露容量常量供断言（与生产单源，防测试硬编码漂移） */
object HLogKeepRingProbe {
    val CAPACITY: Int get() = HLog.capacityForTest()
    val KEEP_CAPACITY: Int get() = HLog.keepCapacityForTest()
}
