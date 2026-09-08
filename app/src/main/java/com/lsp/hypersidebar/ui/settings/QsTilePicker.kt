package com.lsp.hypersidebar.ui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.util.RelayToken
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val TAG = "ShortcutSettings"

/** C3（批次 3）：扫到的单个 TileService 或 manifest 静态快捷方式。 */
data class QsTileInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val appLabel: String,
    /** 非 null=动态/固定快捷方式（桥应答 t=d，启动走 startShortcut 桌面代发）；null=manifest/磁贴 */
    val shortcutId: String? = null
)

/** 按 app 归组后的选择器条目容器（L2 应用列表 / L3 条目共用）。 */
internal data class QsTileAppInfo(
    val packageName: String,
    val appLabel: String,
    val tiles: List<QsTileInfo>,
    val shortcuts: List<QsTileInfo>
)

internal fun loadQsTiles(context: Context): List<QsTileInfo> =
    runCatching {
        val pm = context.packageManager
        pm.queryIntentServices(
            Intent("android.service.quicksettings.action.QS_TILE"), 0
        ).orEmpty()
            .mapNotNull { it.serviceInfo }
            .map { si ->
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(si.packageName, 0)).toString()
                }.getOrNull() ?: si.packageName
                val tileLabel = runCatching { si.loadLabel(pm).toString() }.getOrNull().orEmpty()
                QsTileInfo(
                    packageName = si.packageName,
                    className = si.name,
                    label = tileLabel.ifEmpty { appLabel },
                    appLabel = appLabel
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }.getOrElse { e ->
        Log.w(TAG, "loadQsTiles failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

/**
 * manifest 静态应用快捷方式（与 C2 的 ShortcutProbe 同源数据）：
 * 目标 activity 已知 → 选中后建 COMPONENT 快捷方式 am start 直启（C2 定案，
 * root 可拉非导出目标）。动态/固定 shortcut 不可此法（intent 对非桌面不可见、
 * startShortcut 需桌面角色——B2 归档约束不变）。
 */
internal fun loadManifestShortcuts(context: Context): List<QsTileInfo> =
    runCatching {
        val la = context.getSystemService(LauncherApps::class.java)
            ?: return@runCatching emptyList<QsTileInfo>()
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val pm = context.packageManager
        la.getShortcuts(query, Process.myUserHandle()).orEmpty()
            .also { probe ->
                // 探针（2026-09-05"应用快捷方式数量为0"诊断）：区分 API 返回空 vs 异常
                Log.i(TAG, "ManifestShortcutProbe: raw=${probe.size} " +
                    "withActivity=${probe.count { it.activity != null }}")
            }
            .mapNotNull { si ->
                val cn = si.activity ?: return@mapNotNull null
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString()
                }.getOrNull() ?: cn.packageName
                val label = si.longLabel?.toString() ?: si.shortLabel?.toString().orEmpty()
                QsTileInfo(
                    packageName = cn.packageName,
                    className = cn.className,
                    label = label.ifEmpty { appLabel },
                    appLabel = appLabel
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }.getOrElse { e ->
        Log.w(TAG, "loadManifestShortcuts failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

private fun buildTileApps(context: Context, shortcuts: List<QsTileInfo>): List<QsTileAppInfo> {
    val tiles = loadQsTiles(context)
    val pkgs = LinkedHashSet<String>()
    tiles.forEach { pkgs.add(it.packageName) }
    shortcuts.forEach { pkgs.add(it.packageName) }
    return pkgs.map { pkg ->
        val t = tiles.filter { it.packageName == pkg }
        val s = shortcuts.filter { it.packageName == pkg }
        QsTileAppInfo(pkg, (t.firstOrNull() ?: s.first()).appLabel, t, s)
    }.sortedBy { it.appLabel.lowercase() }
}

/**
 * manifest 快捷方式 launcher 桥（模块侧，2026-09-05 实锤方案）：模块 App 直查
 * getShortcuts 抛 SecurityException（系统只授权默认桌面）→ 向 launcher 进程的
 * hook 接收器发 REQUEST，应答 JSON 经 ShortcutRelayReceiver 回填本桥。
 * 本地 prefs 缓存：冷启动先展示上次清单，应答到达后刷新。
 */
object ManifestShortcutsBridge {

    var shortcuts by mutableStateOf(emptyList<QsTileInfo>())
        private set

    @Volatile private var registered = false

    private const val LOCAL_PREFS = "hyperSidebar_prefs"
    private const val CACHE_KEY = "manifest_shortcuts_cache"

    fun ensureRegistered(context: Context) {
        if (registered) return
        registered = true
        val appCtx = context.applicationContext
        runCatching {
            appCtx.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .getString(CACHE_KEY, null)?.let { json ->
                    shortcuts = parse(json)
                    Log.i(TAG, "manifest shortcuts cache loaded: ${shortcuts.size}")
                }
        }
        runCatching {
            appCtx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        onReply(c, intent.getStringExtra(PrefKeys.MANIFEST_SHORTCUTS_EXTRA) ?: return)
                    }
                },
                IntentFilter(PrefKeys.MANIFEST_SHORTCUTS_REPLY),
                Context.RECEIVER_EXPORTED
            )
        }.onFailure { Log.w(TAG, "manifest bridge register failed: ${it.message}") }
    }

    /** 向 launcher 进程请求最新清单（后台查询+应答，到达后 shortcuts 状态驱动重组）。 */
    fun request(context: Context) {
        runCatching {
            val intent = Intent(PrefKeys.MANIFEST_SHORTCUTS_REQUEST)
            RelayToken.attach(intent, RelayToken.current())
            context.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "manifest bridge request failed: ${it.message}") }
    }

    fun onReply(context: Context, json: String) {
        val list = runCatching { parse(json) }.getOrNull() ?: return
        shortcuts = list
        runCatching {
            context.applicationContext
                .getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString(CACHE_KEY, json).apply()
        }
        Log.i(TAG, "manifest shortcuts received: ${list.size}")
    }

    private fun parse(json: String): List<QsTileInfo> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val ob = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = ob.optString("p")
            if (pkg.isEmpty()) return@mapNotNull null
            // t=d=动态/固定项（s=shortcutId，无 activity 可走）；t=m/旧缓存=manifest 项
            val sid = ob.optString("s").ifEmpty { null }
            val cls = ob.optString("c")
            if (sid == null && cls.isEmpty()) return@mapNotNull null
            QsTileInfo(
                packageName = pkg,
                className = cls,
                label = ob.optString("l").ifEmpty { pkg },
                appLabel = pkg,
                shortcutId = sid
            )
        }
    }
}

/**
 * 快捷开关/应用快捷方式三级选择器（用户 2026-09-05 定稿：
 * L1 类型分组「磁贴/快捷方式」→ L2 应用列表 → L3 可用条目）。
 * 磁贴选中→QS_TILE（SystemUI hook 数据层直点，无须 root、无须固定在控制中心）；
 * 快捷方式选中→
 * COMPONENT（am start 直启目标 activity）。动态 shortcut 因平台约束
 * （B2 归档）不在列。
 */
@Composable
internal fun QsTilePickerPage(
    onSelected: (item: QsTileInfo, isTile: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // manifest 快捷方式桥：注册应答接收器 + 请求 launcher 查询（到达后 key2 驱动重组）
    DisposableEffect(Unit) {
        ManifestShortcutsBridge.ensureRegistered(context)
        ManifestShortcutsBridge.request(context)
        onDispose { }
    }
    val bridgedShortcuts = ManifestShortcutsBridge.shortcuts
    val apps by produceState<List<QsTileAppInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext,
        key2 = bridgedShortcuts
    ) {
        value = withContext(Dispatchers.IO) {
            buildTileApps(context, bridgedShortcuts.ifEmpty { loadManifestShortcuts(context) })
        }
    }

    // 顶部同级类型 Tab（用户 2026-09-05：随时切换）+ 单级返回栈：L3 → L2 → 关闭选择器
    var selectedGroup by remember { mutableStateOf(true) } // true=磁贴 false=快捷方式
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, selectedPackage) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedPackage != null) selectedPackage = null else onBack()
            }
        }
        activity?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    val loading = apps.isEmpty()
    val tileAppCount = apps.count { it.tiles.isNotEmpty() }
    val shortcutAppCount = apps.count { it.shortcuts.isNotEmpty() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            BasicComponent(
                title = stringResource(R.string.qs_tile_picker_title),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                },
                onClick = onBack
            )
        }

        // 类型 Tab（同级，随时切换；切换即回到该类型的应用列表）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GroupTab(
                    text = stringResource(R.string.qs_tile_group_tiles),
                    selected = selectedGroup,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedGroup = true
                        selectedPackage = null
                    }
                )
                GroupTab(
                    text = stringResource(R.string.qs_tile_group_shortcuts),
                    selected = !selectedGroup,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedGroup = false
                        selectedPackage = null
                    }
                )
            }
        }

        if (loading) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.qs_tile_picker_loading),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        if (selectedPackage == null) {
            // L2：当前类型下有可用项的应用列表
            val isTiles = selectedGroup
            val groupApps = apps
                .filter { if (isTiles) it.tiles.isNotEmpty() else it.shortcuts.isNotEmpty() }
                .filter { app ->
                    searchQuery.isBlank() ||
                        app.appLabel.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true) ||
                        (if (isTiles) app.tiles else app.shortcuts).any {
                            it.label.contains(searchQuery, ignoreCase = true)
                        }
                }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = stringResource(R.string.qs_tile_picker_search),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.qs_tile_picker_count, groupApps.size),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(groupApps, key = { it.packageName }) { app ->
                ArrowPreference(
                    title = app.appLabel,
                    summary = stringResource(
                        if (isTiles) R.string.qs_tile_item_count else R.string.qs_shortcut_item_count,
                        if (isTiles) app.tiles.size else app.shortcuts.size
                    ),
                    startAction = {
                        SettingsAppIcon(
                            packageName = app.packageName,
                            appName = app.appLabel,
                            size = 28f
                        )
                    },
                    onClick = { selectedPackage = app.packageName }
                )
            }
        } else {
            // L3：该应用在当前类型下的可用条目
            val app = apps.find { it.packageName == selectedPackage }
            val isTiles = selectedGroup
            if (app != null) {
                val entries = if (isTiles) app.tiles else app.shortcuts
                item {
                    Text(
                        text = app.appLabel,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(entries, key = { (it.shortcutId ?: it.className) + "/" + it.label }) { item ->
                    PickerRow(item) { onSelected(item, isTiles) }
                }
            }
        }
    }
}

/** 类型切换 Tab（同层级、随时可切；选中态 primary 高亮）。 */
@Composable
private fun GroupTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(
            color = if (selected) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    )
}

@Composable
private fun PickerRow(item: QsTileInfo, onClick: () -> Unit) {
    // 摘要=包名 · 类短名，>40 字符截断（§UI 规范：长文本摘要统一观感）
    val raw = "${item.packageName} · ${item.className.substringAfterLast('.')}"
    ArrowPreference(
        title = item.label,
        summary = if (raw.length > 40) raw.take(38) + "…" else raw,
        startAction = {
            SettingsAppIcon(
                packageName = item.packageName,
                appName = item.appLabel,
                size = 28f
            )
        },
        onClick = onClick
    )
}
