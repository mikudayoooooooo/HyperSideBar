package android.app

import android.content.SharedPreferences

/**
 * 平台类名测试双件（2026-11-22）：ShortcutStore.saveUserShortcuts 的防覆盖闸按
 * `javaClass.name == "android.app.SharedPreferencesImpl"` 判本地回退，真平台类在
 * JVM 单测里构造不出来 ⇒ 用同名双件触发闸。任何读写调用都算测试失败——
 * 闸必须在碰 prefs 内容之前就 return false。
 */
class SharedPreferencesImpl : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = throw AssertionError("本地回退不得被读")
    override fun getString(key: String?, defValue: String?): String? =
        throw AssertionError("本地回退不得被读")

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw AssertionError("本地回退不得被读")

    override fun getInt(key: String?, defValue: Int): Int = throw AssertionError("本地回退不得被读")
    override fun getLong(key: String?, defValue: Long): Long = throw AssertionError("本地回退不得被读")
    override fun getFloat(key: String?, defValue: Float): Float = throw AssertionError("本地回退不得被读")
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        throw AssertionError("本地回退不得被读")

    override fun contains(key: String?): Boolean = throw AssertionError("本地回退不得被读")
    override fun edit(): SharedPreferences.Editor = throw AssertionError("本地回退不得被写")
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}