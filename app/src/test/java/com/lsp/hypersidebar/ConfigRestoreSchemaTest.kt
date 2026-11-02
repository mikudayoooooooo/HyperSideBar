package com.lsp.hypersidebar

import com.lsp.hypersidebar.prefs.PrefKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置恢复的类型 schema（2026-11-02 审查 P2）：`CUSTOM_APPS` 是 `StringSet`，
 * 恢复通道必须按 schema 落盘，且非法 JSON 一律拒绝（原实现统一 putString ⇒
 * 恢复该键后 `SettingsRepository.customApps()` 的 getStringSet 抛 ClassCastException）。
 */
class ConfigRestoreSchemaTest {

    @Test
    fun stringSetKind_parsesJsonArrayToStringSet() {
        val parsed = parseRestoreValue("""["com.a","com.b"]""", RestoreKind.JSON_STRING_SET)

        @Suppress("UNCHECKED_CAST")
        val set = parsed as Set<String>
        assertEquals(setOf("com.a", "com.b"), set)
    }

    @Test
    fun stringSetKind_skipsEmptyEntries() {
        val parsed = parseRestoreValue("""["com.a","",""]""", RestoreKind.JSON_STRING_SET)

        @Suppress("UNCHECKED_CAST")
        val set = parsed as Set<String>
        assertEquals(setOf("com.a"), set)
    }

    @Test
    fun jsonArrayKind_returnsRawString() {
        assertEquals("[]", parseRestoreValue("[]", RestoreKind.JSON_ARRAY))
        assertEquals(
            """[{"id":"a"}]""",
            parseRestoreValue("""[{"id":"a"}]""", RestoreKind.JSON_ARRAY)
        )
    }

    @Test
    fun invalidJson_isRejected() {
        assertNull(parseRestoreValue("{oops", RestoreKind.JSON_ARRAY))
        assertNull(parseRestoreValue("not-json", RestoreKind.JSON_STRING_SET))
        assertNull(parseRestoreValue("""{"a":1}""", RestoreKind.JSON_ARRAY))
    }

    @Test
    fun schema_keepsCustomAppsAsStringSetAndExcludesSecurityKeys() {
        assertEquals(RestoreKind.JSON_STRING_SET, RESTORE_SCHEMA[PrefKeys.CUSTOM_APPS])
        assertEquals(RestoreKind.JSON_ARRAY, RESTORE_SCHEMA[PrefKeys.SHORTCUT_ACTIONS])
        assertTrue("令牌绝不能进恢复白名单", PrefKeys.RELAY_TOKEN !in RESTORE_SCHEMA)
    }
}
