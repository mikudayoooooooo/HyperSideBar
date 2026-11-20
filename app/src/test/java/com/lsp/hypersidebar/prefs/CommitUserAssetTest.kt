package com.lsp.hypersidebar.prefs

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户资产键可靠落库（2026-11-20 OS3 实测"删空固定应用后残留最后 1 项"）：
 *
 * 根因两条——apply() 异步落库队列被 kill 截断；空集 putStringSet 在框架 DB 层不生效。
 * 契约：资产键走同步 commit；空集走 remove（删行落库）+ putStringSet 空集（回填内存
 * 快照）两段，缺第二段则 ConfigSync 全量推送 / hook 侧 SyncedPrefs 缺键回落会把旧值
 * 复活，缺第一段则 DB 永远残留旧值。
 *
 * 纯 JVM 假 SharedPreferences（仿 ConfigBackupFilterTest 的 fake 模式），记录 op 序列
 * 并区分 commit/apply。
 */
class CommitUserAssetTest {

    @Test
    fun emptySetIsRemoveThenBackfillBothSyncCommit() {
        val prefs = RecordingPrefs()
        // 预置旧值：删行后回填空集，旧值不得复活
        prefs.map[PrefKeys.CUSTOM_APPS] = setOf("com.old.app")

        val ok = prefs.commitUserAsset(PrefKeys.CUSTOM_APPS, emptySet<String>())

        assertTrue("commitUserAsset 空集应返回 true", ok)
        assertEquals(
            "空集必须先 remove 删行落库、再回填空集，且全程零 apply",
            listOf("commit:remove:customApps", "commit:putStringSet:customApps:0"),
            prefs.ops,
        )
        assertTrue(
            "空集路径不得出现任何 apply（apply 异步队列正是 kill 截断的洞）",
            prefs.ops.none { it.startsWith("apply:") },
        )
        // 回填后内存快照显式带空集：getAll() 推送不会复活旧值
        assertTrue(
            "回填后快照应为空集而非缺键（缺键会让 SyncedPrefs 回落 base 死快照）",
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, null).orEmpty().isEmpty(),
        )
    }

    @Test
    fun nonEmptySetIsSingleSyncCommit() {
        val prefs = RecordingPrefs()
        val ok = prefs.commitUserAsset(PrefKeys.CUSTOM_APPS, setOf("com.a", "com.b"))
        assertTrue("commitUserAsset 非空集应返回 true", ok)
        assertEquals(
            "非空集应是一条同步 commit 的 putStringSet",
            listOf("commit:putStringSet:customApps:2"),
            prefs.ops,
        )
    }

    @Test
    fun stringAssetKeysAreSyncCommit() {
        val order = RecordingPrefs()
        assertTrue(order.commitUserAsset(PrefKeys.CUSTOM_APPS_ORDER, "[]"))
        assertEquals("顺序键（JSON 数组字符串）应同步 commit", listOf("commit:putString:customAppsOrder"), order.ops)

        val actions = RecordingPrefs()
        assertTrue(actions.commitUserAsset(PrefKeys.SHORTCUT_ACTIONS, "[]"))
        assertEquals("快捷方式键应同步 commit", listOf("commit:putString:shortcut_actions"), actions.ops)
    }

    @Test
    fun removeCommitFailureIsObservableAndSkipsBackfill() {
        val prefs = RecordingPrefs(commitResult = false)
        val ok = prefs.commitUserAsset(PrefKeys.CUSTOM_APPS, emptySet<String>())
        assertFalse("删行 commit 失败必须可感知（返回 false）——apply 永远感知不到", ok)
        assertEquals(
            "删行失败时不得执行回填段（DB 里旧行仍在，回填会把内存空集误标为权威）",
            listOf("commit:remove:customApps"),
            prefs.ops,
        )
    }

    @Test
    fun userAssetKeysContractIsExactlyTheThreeAssetKeys() {
        assertEquals(
            "资产键集合增删必须同步改此断言与 savePref 资产分档",
            setOf(PrefKeys.CUSTOM_APPS, PrefKeys.CUSTOM_APPS_ORDER, PrefKeys.SHORTCUT_ACTIONS),
            PrefKeys.USER_ASSET_KEYS,
        )
    }
}

/** 记录 op 序列的假 SharedPreferences：Editor 暂存，commit/apply 时统一入 ops。 */
private class RecordingPrefs(val commitResult: Boolean = true) : SharedPreferences {
    val ops = mutableListOf<String>()
    val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = RecordingEditor(this)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class RecordingEditor(private val prefs: RecordingPrefs) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()
    private var clear = false
    private val opDescs = mutableListOf<String>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
        pending[key] = value
        opDescs += "putString:$key"
    }

    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
        apply {
            pending[key] = values?.toSet()
            opDescs += "putStringSet:$key:${values?.size ?: 0}"
        }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
        pending[key] = value
        opDescs += "putInt:$key"
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
        pending[key] = value
        opDescs += "putLong:$key"
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
        pending[key] = value
        opDescs += "putFloat:$key"
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
        pending[key] = value
        opDescs += "putBoolean:$key"
    }

    override fun remove(key: String): SharedPreferences.Editor = apply {
        removals += key
        opDescs += "remove:$key"
    }

    override fun clear(): SharedPreferences.Editor = apply { clear = true }

    private fun flush() {
        if (clear) prefs.map.clear()
        removals.forEach { prefs.map.remove(it) }
        prefs.map.putAll(pending)
    }

    override fun commit(): Boolean {
        flush()
        prefs.ops += opDescs.map { "commit:$it" }
        return prefs.commitResult
    }

    override fun apply() {
        flush()
        prefs.ops += opDescs.map { "apply:$it" }
    }
}
