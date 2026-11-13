package com.lsp.hypersidebar.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.lsp.hypersidebar.BuildConfig
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份 / 恢复（2026-11-11）：用户设置的导入导出。
 *
 * 为什么是 JSON：设置值已经是 `Boolean/Int/Float/String/StringSet`，快捷方式本来就有
 * `ShortcutAction.toJson()`；JSON 自带结构、可读、可手改，且不需要引入序列化库
 *（`org.json` 是平台/测试均已有）。文件带 `schema` + `version`，将来加键不用换格式。
 *
 * 边界（安全/正确性）：
 * - **只导出白名单键**：`relayToken`、熔断状态、`LAST_RELAY_RESULT`、缓存/统计等运行时
 *   数据一律不进来——否则恢复会携带旧设备的 IPC 状态。
 * - **导入严格校验类型与量程**：坏值整份拒绝（不做“能写多少写多少”），错误信息带键名。
 * - 导入走**单次 commit**（一次同步、一次重组），写完显式 `ConfigSync.notifyConfigChanged`，
 *   hook 侧立即生效；快捷方式仍走 [ShortcutStore.saveUserShortcuts] 的防覆盖闸。
 * - 本文件只定义纯数据逻辑；文件 I/O 在 [exportToDownloads] / [readFromUri]。
 */
object ConfigBackup {

    const val SCHEMA = "hypersidebar.backup"
    const val VERSION = 1

    /** 单份备份文本上限（防病态文件把进程拖死；正常备份 < 100KB）。 */
    const val MAX_CHARS = 512 * 1024

    /** 快捷方式条目上限（远高于产品上限，只拦病态载荷）。 */
    private const val MAX_SHORTCUTS = 200

    /** 固定应用 / 排序键上限（同上）。 */
    private const val MAX_APPS = 512

    private val PKG_RE = Regex("[A-Za-z0-9_.]+")

    // ===== 键白名单（导出/导入的唯一来源） =====

    private enum class Type { BOOL, INT, FLOAT, STRING }

    private data class Spec(
        val key: String,
        val type: Type,
        val default: Any,
        val min: Double = Double.NEGATIVE_INFINITY,
        val max: Double = Double.POSITIVE_INFINITY,
        val allowed: Set<String>? = null,
        val maxLen: Int = 256
    )

    private val SPECS = listOf(
        Spec(PrefKeys.ENABLED, Type.BOOL, true),
        // 主题值不锁枚举：ThemeModes 新增档位时旧备份必须仍可恢复（值只影响观感）
        Spec(PrefKeys.THEME_MODE, Type.STRING, "monet_system"),
        Spec(PrefKeys.ICON_SIZE, Type.FLOAT, LayoutDefaults.ICON_SIZE, 16.0, 120.0),
        Spec(PrefKeys.INNER_RADIUS, Type.FLOAT, LayoutDefaults.INNER_RADIUS, 0.0, 1000.0),
        Spec(PrefKeys.OUTER_RADIUS_MAX, Type.FLOAT, LayoutDefaults.OUTER_RADIUS_MAX, 0.0, 1000.0),
        Spec(PrefKeys.MAX_APPS_OUTER, Type.INT, LayoutDefaults.MAX_APPS_OUTER, 0.0, 20.0),
        Spec(PrefKeys.MAX_APPS_INNER, Type.INT, LayoutDefaults.MAX_APPS_INNER, 0.0, 20.0),
        Spec(
            PrefKeys.LANDSCAPE_ICON_SIZE, Type.FLOAT, LayoutDefaults.LANDSCAPE_ICON_SIZE, 16.0, 120.0
        ),
        Spec(
            PrefKeys.LANDSCAPE_MAX_APPS_OUTER, Type.INT,
            LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER, 0.0, 20.0
        ),
        Spec(
            PrefKeys.LANDSCAPE_MAX_APPS_INNER, Type.INT,
            LayoutDefaults.LANDSCAPE_MAX_APPS_INNER, 0.0, 20.0
        ),
        Spec(
            PrefKeys.LANDSCAPE_INNER_RADIUS, Type.FLOAT,
            LayoutDefaults.LANDSCAPE_INNER_RADIUS, 0.0, 1000.0
        ),
        Spec(
            PrefKeys.LANDSCAPE_OUTER_RADIUS, Type.FLOAT,
            LayoutDefaults.LANDSCAPE_OUTER_RADIUS, 0.0, 1000.0
        ),
        Spec(
            PrefKeys.CORNER_ICON_SIZE, Type.FLOAT, LayoutDefaults.CORNER_ICON_SIZE, 16.0, 120.0
        ),
        Spec(
            PrefKeys.CORNER_INNER_RADIUS, Type.FLOAT,
            LayoutDefaults.CORNER_INNER_RADIUS, 0.0, 1000.0
        ),
        Spec(
            PrefKeys.CORNER_OUTER_RADIUS, Type.FLOAT,
            LayoutDefaults.CORNER_OUTER_RADIUS, 0.0, 1000.0
        ),
        Spec(
            PrefKeys.CORNER_MAX_APPS_OUTER, Type.INT,
            LayoutDefaults.CORNER_MAX_APPS_OUTER, 0.0, 20.0
        ),
        Spec(
            PrefKeys.CORNER_MAX_APPS_INNER, Type.INT,
            LayoutDefaults.CORNER_MAX_APPS_INNER, 0.0, 20.0
        ),
        Spec(PrefKeys.FAN_FOG_INTENSITY, Type.FLOAT, LayoutDefaults.FAN_FOG_INTENSITY, 0.0, 1.0),
        Spec(PrefKeys.FAN_DIM_ENABLED, Type.BOOL, LayoutDefaults.FAN_DIM_ENABLED),
        Spec(
            PrefKeys.FAN_BLUR_SOURCE, Type.STRING, LayoutDefaults.FAN_BLUR_SOURCE_DEFAULT,
            allowed = LayoutDefaults.FAN_BLUR_SOURCE_VALUES.toSet()
        ),
        Spec(PrefKeys.DEAD_ZONE, Type.FLOAT, LayoutDefaults.DEAD_ZONE, 0.0, 200.0),
        Spec(
            PrefKeys.TRIGGER_DWELL_MS, Type.INT, LayoutDefaults.TRIGGER_DWELL_MS,
            LayoutDefaults.TRIGGER_DWELL_MIN_MS.toDouble(),
            LayoutDefaults.TRIGGER_DWELL_MAX_MS.toDouble()
        ),
        Spec(PrefKeys.CORNER_SWIPE_ENABLED, Type.BOOL, LayoutDefaults.CORNER_SWIPE_ENABLED),
        Spec(PrefKeys.CORNER_SPY_FALLBACK, Type.BOOL, LayoutDefaults.CORNER_SPY_FALLBACK),
        Spec(
            PrefKeys.FAN_PERSISTENT_ON_RELEASE, Type.BOOL,
            LayoutDefaults.FAN_PERSISTENT_ON_RELEASE
        ),
        Spec(
            PrefKeys.FREEFORM_BLANK_TAP_ENABLED, Type.BOOL,
            LayoutDefaults.FREEFORM_BLANK_TAP_ENABLED
        ),
        Spec(
            PrefKeys.FREEFORM_BLANK_TAP_MODE, Type.STRING,
            LayoutDefaults.FREEFORM_BLANK_TAP_MODE_DEFAULT,
            allowed = LayoutDefaults.FREEFORM_BLANK_TAP_MODE_VALUES.toSet()
        ),
        Spec(
            PrefKeys.FREEFORM_BLANK_TAP_TAKEOVER, Type.BOOL,
            LayoutDefaults.FREEFORM_BLANK_TAP_TAKEOVER
        ),
        Spec(
            PrefKeys.CORNER_SUPPRESS_ENABLED, Type.BOOL,
            LayoutDefaults.CORNER_SUPPRESS_ENABLED
        )
    )

    /** 快照：`null` = 备份里没有该段（导入时不覆盖对应数据）。 */
    data class Snapshot(
        val settings: Map<String, Any>,
        val customApps: Set<String>?,
        val customAppsOrder: List<String>?,
        val shortcuts: List<ShortcutAction>?,
        val exportedAt: Long = 0L,
        val appVersion: String? = null
    )

    sealed interface ApplyResult {
        data class Success(
            val settingsWritten: Int,
            val shortcutsWritten: Int,
            /** 固定应用中实际写入的数量（已安装）。 */
            val appsWritten: Int,
            /** 固定应用中因未安装被跳过的数量。 */
            val appsSkipped: Int,
        ) : ApplyResult
        data class Rejected(val reason: String) : ApplyResult
    }

    class BackupFormatException(message: String) : IllegalArgumentException(message)

    // ===== 导出 =====

    fun capture(
        prefs: SharedPreferences,
        exportedAt: Long = System.currentTimeMillis(),
        appVersion: String? = BuildConfig.VERSION_NAME
    ): Snapshot {
        val settings = LinkedHashMap<String, Any>(SPECS.size)
        for (spec in SPECS) {
            val value = runCatching {
                when (spec.type) {
                    Type.BOOL -> prefs.getBoolean(spec.key, spec.default as Boolean)
                    Type.INT -> prefs.getInt(spec.key, spec.default as Int)
                    Type.FLOAT -> prefs.getFloat(spec.key, spec.default as Float)
                    Type.STRING -> prefs.getString(spec.key, spec.default as String)
                        ?: spec.default
                }
            }.getOrDefault(spec.default)
            // 脏值（手改 prefs 写入 NaN/Infinity）会让 JSONObject 序列化抛异常 ⇒ 回落默认
            settings[spec.key] = if (spec.type == Type.FLOAT && value is Float && !value.isFinite()) {
                spec.default
            } else {
                value
            }
        }
        val customApps = runCatching {
            prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty()
                .filter { it.length <= 200 && it.matches(PKG_RE) }
                .toSet()
        }.getOrDefault(emptySet())
        val customAppsOrder = readOrder(prefs).filter { it.length <= 200 && it.matches(PKG_RE) }
        val shortcuts = runCatching {
            ShortcutStore.loadUserShortcuts(prefs).filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
        return Snapshot(
            settings = settings,
            customApps = customApps,
            customAppsOrder = customAppsOrder,
            shortcuts = shortcuts,
            exportedAt = exportedAt,
            appVersion = appVersion
        )
    }

    fun encode(snapshot: Snapshot): String {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("exportedAt", snapshot.exportedAt)
        snapshot.appVersion?.let { root.put("appVersion", it) }
        root.put("settings", JSONObject().apply {
            snapshot.settings.forEach { (k, v) -> put(k, v) }
        })
        snapshot.customApps?.let { apps ->
            root.put("customApps", JSONArray().apply { apps.sorted().forEach { put(it) } })
        }
        snapshot.customAppsOrder?.let { order ->
            root.put("customAppsOrder", JSONArray().apply { order.forEach { put(it) } })
        }
        snapshot.shortcuts?.let { list ->
            root.put("shortcuts", JSONArray().apply {
                list.sortedBy { it.order }.forEach { put(it.toJson()) }
            })
        }
        return root.toString(2)
    }

    fun encodeFromPrefs(
        prefs: SharedPreferences,
        appVersion: String? = BuildConfig.VERSION_NAME
    ): String = encode(capture(prefs, appVersion = appVersion))

    // ===== 导入 =====

    fun decode(text: String): Snapshot {
        if (text.length > MAX_CHARS) {
            throw BackupFormatException("备份文件过大（${text.length} 字符，上限 $MAX_CHARS）")
        }
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw BackupFormatException("不是合法 JSON：${it.message}") }
        val schema = root.optString("schema", "")
        if (schema != SCHEMA) {
            throw BackupFormatException("不是 hyperSidebar 备份（schema=$schema）")
        }
        val version = root.optInt("version", -1)
        if (version !in 1..VERSION) {
            throw BackupFormatException("备份版本 $version 不受支持（当前 $VERSION）")
        }

        val settings = LinkedHashMap<String, Any>()
        if (root.has("settings") && root.optJSONObject("settings") == null) {
            throw BackupFormatException("settings 应为对象")
        }
        root.optJSONObject("settings")?.let { obj ->
            for (spec in SPECS) {
                if (!obj.has(spec.key)) continue
                settings[spec.key] = decodeValue(spec, obj.get(spec.key))
            }
        }

        if (root.has("customApps") && root.optJSONArray("customApps") == null) {
            throw BackupFormatException("customApps 应为数组")
        }
        if (root.has("customAppsOrder") && root.optJSONArray("customAppsOrder") == null) {
            throw BackupFormatException("customAppsOrder 应为数组")
        }
        if (root.has("shortcuts") && root.optJSONArray("shortcuts") == null) {
            throw BackupFormatException("shortcuts 应为数组")
        }
        val customApps = root.optJSONArray("customApps")?.let { arr ->
            if (arr.length() > MAX_APPS) throw BackupFormatException("customApps 条目过多")
            val out = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i, "")
                if (!pkg.matches(PKG_RE)) throw BackupFormatException("customApps 第 $i 项非法：$pkg")
                out.add(pkg)
            }
            out
        }

        val order = root.optJSONArray("customAppsOrder")?.let { arr ->
            if (arr.length() > MAX_APPS) throw BackupFormatException("customAppsOrder 条目过多")
            (0 until arr.length()).map {
                val pkg = arr.optString(it, "")
                if (!pkg.matches(PKG_RE)) throw BackupFormatException("customAppsOrder 第 $it 项非法：$pkg")
                pkg
            }
        }

        val shortcuts = root.optJSONArray("shortcuts")?.let { arr ->
            if (arr.length() > MAX_SHORTCUTS) throw BackupFormatException("快捷方式条目过多")
            val list = ArrayList<ShortcutAction>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                    ?: throw BackupFormatException("shortcuts 第 $i 项不是对象")
                val item = runCatching { ShortcutAction.fromJson(obj) }
                    .getOrElse { throw BackupFormatException("shortcuts 第 $i 项解析失败：${it.message}") }
                if (item.id.isBlank()) throw BackupFormatException("shortcuts 第 $i 项缺少 id")
                if (item.label.length > 200) throw BackupFormatException("shortcuts 第 $i 项 label 过长")
                if (item.source == ShortcutSource.USER) list.add(item)
            }
            ShortcutGroups.normalize(list.sortedBy { it.order })
        }

        return Snapshot(
            settings = settings,
            customApps = customApps,
            customAppsOrder = order,
            shortcuts = shortcuts,
            exportedAt = root.optLong("exportedAt", 0L),
            appVersion = root.optString("appVersion", "").ifEmpty { null }
        )
    }

    /**
     * 应用快照：单次 commit；快捷方式交给 [ShortcutStore]（保留其防覆盖闸）。
     * 本地回退 prefs（LSPosed 未绑定）**不允许写快捷方式**——那正是 2026-10-19 数据事故
     * 的形态（本地空壳把远端完整列表整表覆盖）；纯设置导入仍允许，只是本地生效。
     *
     * [isPackageInstalled]：导入固定应用时逐包校验；未安装的条目跳过（不写入），
     * 结果里返回成功/跳过数量（2026-11-13 用户要求）。
     */
    fun apply(
        prefs: SharedPreferences,
        snapshot: Snapshot,
        isPackageInstalled: (String) -> Boolean = { true },
    ): ApplyResult {
        val localFallback = prefs.javaClass.name == "android.app.SharedPreferencesImpl"
        if (localFallback && !snapshot.shortcuts.isNullOrEmpty()) {
            return ApplyResult.Rejected(
                "LSPosed 未绑定：快捷方式只能写入 remotePrefs，已拒绝（避免覆盖远端列表）"
            )
        }
        val editor = prefs.edit()
        var settingsWritten = 0
        snapshot.settings.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, (value as Set<String>).toHashSet())
                }
                else -> return@forEach
            }
            settingsWritten++
        }
        // 固定应用：备份里可能含当前设备未安装的包，直接写入会让它们出现在列表里但无法启动。
        // 跳过未安装项，并返回成功/失败计数；customAppsOrder 同样过滤，保持顺序表可用。
        val keptApps = snapshot.customApps?.filter { pkg ->
            runCatching { isPackageInstalled(pkg) }.getOrDefault(false)
        }
        val keptOrder = snapshot.customAppsOrder?.filter { pkg ->
            runCatching { isPackageInstalled(pkg) }.getOrDefault(false)
        }
        snapshot.customApps?.let {
            editor.putStringSet(PrefKeys.CUSTOM_APPS, keptApps.orEmpty().toHashSet())
            settingsWritten++
        }
        snapshot.customAppsOrder?.let {
            editor.putString(PrefKeys.CUSTOM_APPS_ORDER, JSONArray(keptOrder.orEmpty()).toString())
            settingsWritten++
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (!committed) return ApplyResult.Rejected("写入配置失败（SharedPreferences.commit 返回 false）")
        if (!localFallback) {
            snapshot.shortcuts?.let { ShortcutStore.saveUserShortcuts(prefs, it) }
        }
        ConfigSync.notifyConfigChanged("(importBackup)")
        val appsWritten = when {
            snapshot.customApps != null -> keptApps?.size ?: 0
            snapshot.customAppsOrder != null -> keptOrder?.size ?: 0
            else -> 0
        }
        val appsTotal = snapshot.customApps?.size ?: snapshot.customAppsOrder?.size ?: 0
        return ApplyResult.Success(
            settingsWritten = settingsWritten,
            shortcutsWritten = snapshot.shortcuts?.size ?: 0,
            appsWritten = appsWritten,
            appsSkipped = (appsTotal - appsWritten).coerceAtLeast(0),
        )
    }

    // ===== 文件 I/O =====

    suspend fun exportToDownloads(context: Context, json: String): String {
        val fileName = "hypersidebar_backup_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
        return DownloadsWriter.write(context, fileName, "application/json", json)
    }

    /** 读取用户选中的备份文件（字符上限 [MAX_CHARS]；超限在 [decode] 里也会再拒一次）。 */
    suspend fun readFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val reader = input.bufferedReader()
            val sb = StringBuilder(4096)
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length > MAX_CHARS) throw BackupFormatException("备份文件过大（上限 $MAX_CHARS 字符）")
            }
            sb.toString()
        } ?: throw BackupFormatException("无法打开所选文件")
    }

    // ===== 内部 =====

    private fun readOrder(prefs: SharedPreferences): List<String> = runCatching {
        val raw = prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            arr.optString(it, "").takeIf { pkg -> pkg.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    private fun decodeValue(spec: Spec, raw: Any?): Any = when (spec.type) {
        Type.BOOL -> raw as? Boolean
            ?: throw BackupFormatException("${spec.key} 应为布尔值")
        Type.INT -> {
            val n = raw as? Number ?: throw BackupFormatException("${spec.key} 应为整数")
            val d = n.toDouble()
            if (!d.isFinite() || d != Math.floor(d)) {
                throw BackupFormatException("${spec.key} 应为整数（收到 $raw）")
            }
            if (d < spec.min || d > spec.max) {
                throw BackupFormatException("${spec.key}=$raw 超出范围 ${spec.min.toInt()}..${spec.max.toInt()}")
            }
            d.toInt()
        }
        Type.FLOAT -> {
            val n = raw as? Number ?: throw BackupFormatException("${spec.key} 应为数字")
            val d = n.toDouble()
            if (!d.isFinite()) throw BackupFormatException("${spec.key} 不是有限数")
            if (d < spec.min || d > spec.max) {
                throw BackupFormatException("${spec.key}=$raw 超出范围 ${spec.min}..${spec.max}")
            }
            d.toFloat()
        }
        Type.STRING -> {
            val s = raw as? String ?: throw BackupFormatException("${spec.key} 应为字符串")
            if (s.length > spec.maxLen) throw BackupFormatException("${spec.key} 过长")
            if (spec.allowed != null && s !in spec.allowed) {
                throw BackupFormatException("${spec.key}=$s 不是合法取值（${spec.allowed.joinToString()}）")
            }
            s
        }
    }
}
