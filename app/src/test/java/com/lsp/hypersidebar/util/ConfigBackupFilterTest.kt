package com.lsp.hypersidebar.util

import android.content.SharedPreferences
import com.lsp.hypersidebar.prefs.PrefKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份导入的固定应用过滤（2026-11-13 用户要求）：
 * 备份里当前设备不存在的包必须跳过，且返回成功/失败计数；顺序表同步过滤。
 *
 * 用纯 JVM 的假 SharedPreferences 调 [ConfigBackup.apply]，不依赖 Robolectric。
 */
class ConfigBackupFilterTest {

    @Test
    fun `missing fixed apps are skipped and counted`() {
        val prefs = FakePrefs()
        val snapshot = ConfigBackup.Snapshot(
            settings = emptyMap(),
            customApps = linkedSetOf("com.installed.app", "com.missing.app"),
            customAppsOrder = listOf("com.installed.app", "com.missing.app"),
            shortcuts = null,
        )

        val result = ConfigBackup.apply(prefs, snapshot) { it == "com.installed.app" }

        assertTrue("导入应成功", result is ConfigBackup.ApplyResult.Success)
        result as ConfigBackup.ApplyResult.Success
        assertEquals("成功固定应用数", 1, result.appsWritten)
        assertEquals("跳过固定应用数", 1, result.appsSkipped)
        assertEquals(
            "只写入已安装的固定应用",
            setOf("com.installed.app"),
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, mutableSetOf<String>()),
        )
        assertEquals(
            "顺序表只保留已安装项",
            "[\"com.installed.app\"]",
            prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null),
        )
    }

    @Test
    fun `all installed apps keep counts zero skipped`() {
        val prefs = FakePrefs()
        val snapshot = ConfigBackup.Snapshot(
            settings = emptyMap(),
            customApps = setOf("a", "b"),
            customAppsOrder = listOf("a", "b"),
            shortcuts = null,
        )

        val result = ConfigBackup.apply(prefs, snapshot) { true }

        result as ConfigBackup.ApplyResult.Success
        assertEquals(2, result.appsWritten)
        assertEquals(0, result.appsSkipped)
    }

    @Test
    fun `no fixed apps section leaves counts zero`() {
        val prefs = FakePrefs()
        val snapshot = ConfigBackup.Snapshot(
            settings = mapOf(PrefKeys.TRIGGER_DWELL_MS to 250),
            customApps = null,
            customAppsOrder = null,
            shortcuts = null,
        )

        val result = ConfigBackup.apply(prefs, snapshot) { true }

        result as ConfigBackup.ApplyResult.Success
        assertEquals(0, result.appsWritten)
        assertEquals(0, result.appsSkipped)
        assertEquals(1, result.settingsWritten)
    }

    @Test
    fun `importing zero kept apps removes row and backfills empty set`() {
        val prefs = FakePrefs()
        // 预置 DB 旧值：旧版空集 put 在框架 DB 层不生效，会残留此值（2026-11-20 实测根因）
        prefs.edit().putStringSet(PrefKeys.CUSTOM_APPS, hashSetOf("com.stale.app")).commit()
        val snapshot = ConfigBackup.Snapshot(
            settings = emptyMap(),
            customApps = linkedSetOf("com.missing.app"),
            customAppsOrder = listOf("com.missing.app"),
            shortcuts = null,
        )

        val result = ConfigBackup.apply(prefs, snapshot) { false }

        result as ConfigBackup.ApplyResult.Success
        assertEquals("全部未安装：写入 0", 0, result.appsWritten)
        assertEquals("全部未安装：跳过 1", 1, result.appsSkipped)
        // 删行 + 回填空集：快照必须显式带空集而非残留旧值（ConfigSync 全量推送与
        // hook 侧 SyncedPrefs 缺键回落都会复活缺键旧值）
        assertEquals(
            "空导入后快照必须是空集而非残留旧值",
            setOf<String>(),
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, null),
        )
        assertEquals("顺序表同步清空", "[]", prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null))
    }
}

/** 只实现 ConfigBackup.apply 用到的 SharedPreferences/Editor 面。 */
private class FakePrefs : SharedPreferences {
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
    override fun edit(): SharedPreferences.Editor = FakeEditor(map)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

private class FakeEditor(private val map: HashMap<String, Any?>) : SharedPreferences.Editor {
    private val pending = HashMap<String, Any?>()
    private val removals = HashSet<String>()
    private var clear = false

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

    override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

    override fun clear(): SharedPreferences.Editor = apply { clear = true }

    override fun commit(): Boolean {
        if (clear) map.clear()
        removals.forEach { map.remove(it) }
        map.putAll(pending)
        return true
    }

    override fun apply() {
        commit()
    }
}
