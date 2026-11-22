package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存结果传播（2026-11-22「调序后保存不上」修复的守卫）：
 * saveUserShortcuts 拒存/写失败必须返回 false，且四个 CRUD 入口不得把失败吞成 true——
 * 静默成功假象正是该 bug 的用户观感（UI 内存新序、磁盘旧序）。
 * 防覆盖闸按平台类名判定，双件见 test 源集 android.app.SharedPreferencesImpl。
 */
class ShortcutStoreSaveResultTest {

    private fun item(id: String, order: Int = 0) = ShortcutAction(
        id = id,
        kind = ShortcutKind.ACTIVITY,
        label = id,
        packageName = "com.example.$id",
        order = order
    )

    @Test
    fun save成功返回true且order按列表序重排() {
        val prefs = SaveResultPrefs()
        val ok = ShortcutStore.saveUserShortcuts(prefs, listOf(item("b", 7), item("a", 3)))
        assertTrue("commit 成功必须返回 true", ok)
        val loaded = ShortcutStore.loadUserShortcuts(prefs)
        assertEquals(listOf("b", "a"), loaded.map { it.id })
        assertEquals(listOf(0, 1), loaded.map { it.order })
    }

    @Test
    fun commit返回false时save返回false() {
        val prefs = SaveResultPrefs(commitResult = false)
        assertFalse("commit=false 不得报成功", ShortcutStore.saveUserShortcuts(prefs, listOf(item("a"))))
    }

    @Test
    fun commit抛异常时save返回false() {
        val prefs = SaveResultPrefs(throwOnCommit = true)
        assertFalse("写异常不得报成功", ShortcutStore.saveUserShortcuts(prefs, listOf(item("a"))))
    }

    @Test
    fun 本地回退类名触发防覆盖闸且不碰prefs() {
        val prefs = android.app.SharedPreferencesImpl()
        assertFalse("防覆盖闸必须返回 false", ShortcutStore.saveUserShortcuts(prefs, listOf(item("a"))))
    }

    @Test
    fun addShortcut传播写失败() {
        val prefs = SaveResultPrefs(commitResult = false)
        assertFalse("add 落盘失败必须返回 false", ShortcutStore.addShortcut(prefs, item("a")))
        assertTrue(SaveResultPrefs().let { ShortcutStore.addShortcut(it, item("a")) })
    }

    @Test
    fun updateShortcut传播写失败() {
        val prefs = SaveResultPrefs()
        assertTrue(ShortcutStore.addShortcut(prefs, item("a")))
        prefs.commitResult = false
        assertFalse(
            "update 落盘失败必须返回 false",
            ShortcutStore.updateShortcut(prefs, item("a").copy(label = "改名"))
        )
    }

    @Test
    fun removeShortcut传播写失败() {
        val prefs = SaveResultPrefs()
        assertTrue(ShortcutStore.addShortcut(prefs, item("a")))
        prefs.commitResult = false
        assertFalse("remove 落盘失败必须返回 false", ShortcutStore.removeShortcut(prefs, "a"))
    }

    @Test
    fun moveShortcut成功重排且传播写失败() {
        val prefs = SaveResultPrefs()
        assertTrue(ShortcutStore.saveUserShortcuts(prefs, listOf(item("a"), item("b"), item("c"))))
        assertTrue(ShortcutStore.moveShortcut(prefs, 0, 2))
        assertEquals(listOf("b", "c", "a"), ShortcutStore.loadUserShortcuts(prefs).map { it.id })
        prefs.commitResult = false
        assertFalse("move 落盘失败必须返回 false", ShortcutStore.moveShortcut(prefs, 0, 1))
    }
}

/**  map 承载 + commit 结果/异常可控的假 prefs（仿 ConfigBackupFilterTest 的 fake 模式）。 */
private class SaveResultPrefs(
    var commitResult: Boolean = true,
    private val throwOnCommit: Boolean = false
) : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? =
        map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = SaveResultEditor(map, commitResult, throwOnCommit)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class SaveResultEditor(
    private val map: HashMap<String, Any?>,
    private val commitResult: Boolean,
    private val throwOnCommit: Boolean
) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
        pending[key] = value
    }

    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
        apply { pending[key] = values?.toSet() }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
        pending[key] = value
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
        pending[key] = value
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
        pending[key] = value
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
        pending[key] = value
    }

    override fun remove(key: String): SharedPreferences.Editor = apply { pending.remove(key) }

    override fun clear(): SharedPreferences.Editor = apply { map.clear() }

    override fun commit(): Boolean {
        if (throwOnCommit) throw RuntimeException("模拟写盘失败")
        if (commitResult) map.putAll(pending)
        return commitResult
    }

    override fun apply() {
        commit()
    }
}