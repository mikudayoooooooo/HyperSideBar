package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val TAG = "ShortcutSettings"

/** C3（批次 3）：扫到的单个 TileService 或 manifest 静态快捷方式。 */
internal data class QsTileInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val appLabel: String
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

private fun buildTileApps(context: Context): List<QsTileAppInfo> {
    val tiles = loadQsTiles(context)
    val shortcuts = loadManifestShortcuts(context)
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
 * 快捷开关/应用快捷方式三级选择器（用户 2026-09-05 定稿：
 * L1 类型分组「磁贴/快捷方式」→ L2 应用列表 → L3 可用条目）。
 * 磁贴选中→QS_TILE（root click-tile 触发，须已在 QS）；快捷方式选中→
 * COMPONENT（am start 直启目标 activity）。动态 shortcut 因平台约束
 * （B2 归档）不在列。
 */
@Composable
internal fun QsTilePickerPage(
    onSelected: (item: QsTileInfo, isTile: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val apps by produceState<List<QsTileAppInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext
    ) {
        value = withContext(Dispatchers.IO) { buildTileApps(context) }
    }

    // 内部两级返回栈：L3 → L2 → L1 → 关闭选择器（回到编辑页）
    var selectedGroup by remember { mutableStateOf<Boolean?>(null) } // true=磁贴 false=快捷方式
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, selectedGroup, selectedPackage) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedPackage != null -> selectedPackage = null
                    selectedGroup != null -> selectedGroup = null
                    else -> onBack()
                }
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

        if (loading && selectedGroup == null) {
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

        when {
            // L1：类型分组入口
            selectedGroup == null -> {
                item {
                    ArrowPreference(
                        title = stringResource(R.string.qs_tile_group_tiles),
                        summary = stringResource(R.string.qs_tile_group_apps_count, tileAppCount),
                        onClick = {
                            selectedGroup = true
                            searchQuery = ""
                        }
                    )
                }
                item {
                    ArrowPreference(
                        title = stringResource(R.string.qs_tile_group_shortcuts),
                        summary = stringResource(R.string.qs_tile_group_apps_count, shortcutAppCount),
                        onClick = {
                            selectedGroup = false
                            searchQuery = ""
                        }
                    )
                }
            }

            // L2：该类型下有可用项的应用列表
            selectedPackage == null -> {
                val isTiles = selectedGroup == true
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
            }

            // L3：该应用在该类型下的可用条目
            else -> {
                val app = apps.find { it.packageName == selectedPackage }
                val isTiles = selectedGroup == true
                if (app != null) {
                    val entries = if (isTiles) app.tiles else app.shortcuts
                    item {
                        Text(
                            text = stringResource(
                                if (isTiles) R.string.qs_tile_group_tiles else R.string.qs_tile_group_shortcuts
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(entries, key = { it.className + "/" + it.label }) { item ->
                        PickerRow(item) { onSelected(item, isTiles) }
                    }
                }
            }
        }
    }
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
