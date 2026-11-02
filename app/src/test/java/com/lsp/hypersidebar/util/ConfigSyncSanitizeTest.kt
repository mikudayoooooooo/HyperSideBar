package com.lsp.hypersidebar.util

import com.lsp.hypersidebar.prefs.PrefKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sanitizeSyncMap] 纯逻辑单测：伪造/损坏的同步载荷不得进入 hook 侧缓存。
 *
 * 只测纯函数——[ConfigSync.applySync] 依赖 `SystemClock.elapsedRealtime()`/`HLog`（JVM
 * 单测里是 android.jar 桩），本仓库不开 `returnDefaultValues`，故不为它放宽测试配置。
 */
class ConfigSyncSanitizeTest {

    @Test
    fun dropsRelayToken_keepsBasicTypes() {
        val out = sanitizeSyncMap(
            mapOf(
                PrefKeys.RELAY_TOKEN to "forged-token",
                "iconSize" to 1.2f,
                "enabled" to true,
            )
        )

        assertNull("令牌绝不能被广播通道写入缓存", out[PrefKeys.RELAY_TOKEN])
        assertEquals(1.2f, out["iconSize"] as Float)
        assertTrue(out["enabled"] as Boolean)
        assertEquals(2, out.size)
    }

    @Test
    fun keepsStringSetAsSetOfString() {
        val out = sanitizeSyncMap(mapOf(PrefKeys.CUSTOM_APPS to setOf("com.a", "com.b")))

        @Suppress("UNCHECKED_CAST")
        val apps = out[PrefKeys.CUSTOM_APPS] as Set<String>
        assertEquals(setOf("com.a", "com.b"), apps)
    }

    @Test
    fun dropsNonStringSetAndOversizedValues() {
        val out = sanitizeSyncMap(
            mapOf(
                PrefKeys.CUSTOM_APPS to setOf(1, 2),           // 非 String 元素 ⇒ 整键丢弃
                "big" to "x".repeat(70_000),                    // 超 64KB ⇒ 丢弃
                1 to "non-string-key",                          // 键不是 String ⇒ 丢弃
                "nested" to mapOf("a" to 1),                    // 非基础类型 ⇒ 丢弃
                "ok" to 42,
            )
        )

        assertFalse(out.containsKey(PrefKeys.CUSTOM_APPS))
        assertFalse(out.containsKey("big"))
        assertFalse(out.containsKey("nested"))
        assertTrue("只剩 ok 一个键：$out", out.keys == setOf("ok"))
    }

    @Test
    fun dropsOversizedStringSet() {
        val huge = (0 until 600).map { "pkg.$it" }.toSet()
        val out = sanitizeSyncMap(mapOf(PrefKeys.CUSTOM_APPS to huge))

        assertFalse(out.containsKey(PrefKeys.CUSTOM_APPS))
    }
}
