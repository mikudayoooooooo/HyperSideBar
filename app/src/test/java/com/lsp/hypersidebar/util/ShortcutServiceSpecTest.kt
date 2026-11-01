package com.lsp.hypersidebar.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SERVICE 快捷方式的 action/extras 纯核心（2026-11-01）。
 *
 * 守卫三件容易静默错的事：
 * 1. 文本 ↔ Map 的归一化（`k=v` 里值带 `=`、空行/注释、非法键丢弃）；
 * 2. JSON 往返不丢 action/extras（扇环装配→落盘→重启读回是同一条链）；
 * 3. 直启与 root 兜底拼出**同一条命令**（曾经"只在一种策略下才带 extras"就退化）。
 */
class ShortcutServiceSpecTest {

    private val spec = ShortcutServiceSpec

    @Test
    fun `parse 保留值里的等号并丢弃非法行`() {
        val text = """
            # 注释
            voice_assist_start_from_key=long_press_fullscreen_gesture_line
            query=a=b=c

            坏键=值
            noEquals
        """.trimIndent()
        val map = spec.parse(text)
        assertEquals(2, map.size)
        assertEquals("long_press_fullscreen_gesture_line", map["voice_assist_start_from_key"])
        assertEquals("a=b=c", map["query"])
    }

    @Test
    fun `format 与 parse 往返稳定`() {
        val original = mapOf("b_key" to "2", "a_key" to "1")
        val text = spec.format(original)
        assertEquals("a_key=1\nb_key=2", text)
        assertEquals(original, spec.parse(text))
    }

    @Test
    fun `extras 超过上限时截断而不是抛`() {
        val text = (1..20).joinToString("\n") { "k$it=v$it" }
        assertEquals(spec.MAX_EXTRAS, spec.parse(text).size)
    }

    @Test
    fun `action 校验拒绝 shell 危险字符`() {
        assertTrue(spec.validAction(null))
        assertTrue(spec.validAction("android.intent.action.ASSIST"))
        assertFalse(spec.validAction("android.intent.action.ASSIST'; rm -rf /"))
        assertFalse(spec.validAction("a b"))
    }

    @Test
    fun `am startservice 命令与小爱识屏参数一致`() {
        // 参数口径见 docs/adaptation/xiaoai-gestureline-launch.md §6（不进 UI 预设，进文档）
        val service = "com.xiaomi.voiceassistant.VoiceService"
        val extras = linkedMapOf(
            "voice_assist_start_from_key" to "long_press_fullscreen_gesture_line",
            "voice_assist_function_key" to "start_screen_recognition",
        )
        val cmd = spec.amStartServiceCommand(
            "com.miui.voiceassist", service, "android.intent.action.ASSIST", extras
        ) ?: error("command must not be null")
        assertEquals(
            listOf(
                "am", "startservice",
                "-a", "android.intent.action.ASSIST",
                "-n", "com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService",
                "--es", "voice_assist_start_from_key", "long_press_fullscreen_gesture_line",
                "--es", "voice_assist_function_key", "start_screen_recognition",
            ),
            cmd
        )
    }

    @Test
    fun `无 action 无 extras 时退回纯 component 命令`() {
        val cmd = spec.amStartServiceCommand("com.example", "com.example.Svc", null, emptyMap())
        assertEquals(listOf("am", "startservice", "-n", "com.example/com.example.Svc"), cmd)
    }

    @Test
    fun `非法 extras 值让命令整体作废`() {
        assertNull(
            spec.amStartServiceCommand(
                "com.example", "com.example.Svc", null, mapOf("k" to "line\nbreak")
            )
        )
        assertNull(
            spec.amStartServiceCommand(
                "com.example", "com.example.Svc", "bad action", emptyMap()
            )
        )
    }

    @Test
    fun `ShortcutAction JSON 往返保留 action 与 extras`() {
        val original = ShortcutAction(
            id = "sc-xiaoai",
            kind = ShortcutKind.SERVICE,
            label = "小爱识屏",
            packageName = "com.miui.voiceassist",
            serviceName = "com.xiaomi.voiceassistant.VoiceService",
            action = "android.intent.action.ASSIST",
            extras = mapOf(
                "voice_assist_start_from_key" to "long_press_fullscreen_gesture_line",
                "voice_assist_function_key" to "start_screen_recognition",
            ),
        )
        val back = ShortcutAction.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.action, back.action)
        assertEquals(original.extras, back.extras)
        assertEquals(original.serviceName, back.serviceName)
    }

    @Test
    fun `旧数据没有 action 字段时解析为 null`() {
        val json = JSONObject().apply {
            put("id", "old")
            put("kind", "SERVICE")
            put("label", "旧条目")
            put("packageName", "com.example")
            put("serviceName", "com.example.Svc")
        }
        val back = ShortcutAction.fromJson(json)
        assertNull(back.action)
        assertTrue(back.extras.isEmpty())
    }
}
