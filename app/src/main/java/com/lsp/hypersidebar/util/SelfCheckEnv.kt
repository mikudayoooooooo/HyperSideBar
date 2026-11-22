package com.lsp.hypersidebar.util

import com.lsp.hypersidebar.hook.CornerPilferPolicy

/**
 * 自检环境头部（2026-11-22 增强 3，纯函数）：ROM 线 / 双版本号 / 抢流分档判决 /
 * native gate / 三宿主回执年龄。issue #13 的判读曾靠人肉从散落日志行拼这些结论
 * （launcher versionCode 在 spy 行里、分档在代码里、回执在 ack 行里）——头部直接打印。
 *
 * 分档判决真调 [CornerPilferPolicy]，不另写判据：prop 语义（sysjudge 非 "0" 即开、
 * pilfer 认 "1"/"0"）、阈值、读不到保守开，全部单源。
 */
object SelfCheckEnv {

    fun lines(
        rom: String,
        os4: Boolean,
        launcherVc: Long,
        moduleVc: Long,
        pilferProp: String,
        sysjudgeProp: String,
        gateMarker: String?,
        acks: Map<String, ConfigSync.Ack>,
        nowElapsedMs: Long
    ): List<String> = buildList {
        add("ROM: $rom os4=$os4")
        add("launcher versionCode=$launcherVc module versionCode=$moduleVc")
        add(
            "2a sysjudge=${onOff(CornerPilferPolicy.decideSysJudge(sysjudgeProp, launcherVc))} " +
                "(prop=\"$sysjudgeProp\" threshold=${CornerPilferPolicy.REVOKED_FROM})"
        )
        add("corner pilfer=${onOff(CornerPilferPolicy.decide(pilferProp, launcherVc))} (prop=\"$pilferProp\")")
        add("native gate marker=${gateMarker ?: "<absent>"}")
        acks.values.sortedBy { it.proc ?: "" }.forEach { a ->
            add(
                "ack proc=${a.proc ?: "?"} keys=${a.keys} vc=${a.vc} " +
                    "age=${(nowElapsedMs - a.at).coerceAtLeast(0L) / 1000}s"
            )
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"
}