package com.lsp.hypersidebar.util

import com.lsp.hypersidebar.hook.CornerPilferPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检环境头部（2026-11-22 增强 3）：把 issue #13 里要靠人肉拼的结论变成打印值。
 * 分档判决必须真调 [CornerPilferPolicy]（prop 语义/阈值/读不到保守开），
 * 不得在头部里另写一份判据——两份判据漂移就是"头部说关、实际开"的新盲区。
 */
class SelfCheckEnvTest {

    private fun lines(
        launcherVc: Long = 801027755L,
        pilferProp: String = "",
        sysjudgeProp: String = "",
        gateMarker: String? = null,
        acks: Map<String, ConfigSync.Ack> = emptyMap(),
        now: Long = 100_000L
    ): List<String> = SelfCheckEnv.lines(
        rom = "OS4.0.6.0.XKPCNXM",
        os4 = true,
        launcherVc = launcherVc,
        moduleVc = 2026112201L,
        pilferProp = pilferProp,
        sysjudgeProp = sysjudgeProp,
        gateMarker = gateMarker,
        acks = acks,
        nowElapsedMs = now
    )

    private fun joined(l: List<String>) = l.joinToString("\n")

    @Test
    fun 旧桌面且prop未设时两档皆开() {
        val s = joined(lines())
        assertTrue("8602 前 + 空 prop ⇒ 2a 应开：$s", s.contains("2a sysjudge=on"))
        assertTrue("8602 前 + 空 prop ⇒ pilfer 应开：$s", s.contains("corner pilfer=on"))
    }

    @Test
    fun 达阈值桌面且prop未设时两档皆关() {
        val s = joined(lines(launcherVc = CornerPilferPolicy.REVOKED_FROM))
        assertTrue("8602 起 ⇒ 2a 应关：$s", s.contains("2a sysjudge=off"))
        assertTrue("8602 起 ⇒ pilfer 应关：$s", s.contains("corner pilfer=off"))
    }

    @Test
    fun 显式prop覆盖版本分档() {
        val s = joined(lines(launcherVc = 801027755L, pilferProp = "0", sysjudgeProp = "1"))
        assertTrue("sysjudge=1 覆盖旧桌面以外的语义仍开：$s", s.contains("2a sysjudge=on"))
        assertTrue("pilfer=0 显式关：$s", s.contains("corner pilfer=off"))
    }

    @Test
    fun 读不到桌面版本时保守开() {
        val s = joined(lines(launcherVc = 0L))
        assertTrue("vc=0 ⇒ 2a 保守开：$s", s.contains("2a sysjudge=on"))
        assertTrue("vc=0 ⇒ pilfer 保守开：$s", s.contains("corner pilfer=on"))
    }

    @Test
    fun 头部带ROM与双版本号与gate() {
        val s = joined(lines(gateMarker = "0"))
        assertTrue(s.contains("OS4.0.6.0.XKPCNXM"))
        assertTrue(s.contains("launcher versionCode=801027755"))
        assertTrue(s.contains("module versionCode=2026112201"))
        assertTrue("gate marker 内容要原样带出：$s", s.contains("native gate marker=0"))
        assertTrue(joined(lines(gateMarker = null)).contains("native gate marker=<absent>"))
    }

    @Test
    fun ack行带进程与版本与年龄() {
        val acks = mapOf("launcher" to ConfigSync.Ack("launcher", 8, 2026111402, 40_000L))
        val s = joined(lines(acks = acks, now = 100_000L))
        assertTrue("ack 行缺 proc：$s", s.contains("ack proc=launcher"))
        assertTrue("ack 行缺 vc（陈旧宿主判据）：$s", s.contains("vc=2026111402"))
        assertTrue("ack 年龄应为 60s：$s", s.contains("age=60s"))
    }
}