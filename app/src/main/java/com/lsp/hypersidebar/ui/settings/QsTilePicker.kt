package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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

/** C3（批次 3）：扫到的单个 TileService。label 取磁贴标签，缺省回退应用名。 */
internal data class QsTileInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val appLabel: String
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

/**
 * 快捷开关/应用快捷方式平铺选择页（用户 2026-09-05 拍板：添加入口与组件/Intent URI
 * 同级，独立平铺不走"应用→服务"两级翻找）。两组：
 * - 控制中心磁贴（TileService）：建 QS_TILE 快捷方式，root click-tile 触发（须已在 QS）
 * - 应用快捷方式（manifest 静态）：建 COMPONENT 快捷方式，am start 直启目标 activity
 * 选中回填编辑页。动态 shortcut 因平台约束（B2 归档）不在列。
 */
@Composable
internal fun QsTilePickerPage(
    onSelected: (item: QsTileInfo, isTile: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val tiles by produceState<List<QsTileInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext
    ) {
        value = withContext(Dispatchers.IO) { loadQsTiles(context) }
    }
    val shortcuts by produceState<List<QsTileInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext
    ) {
        value = withContext(Dispatchers.IO) { loadManifestShortcuts(context) }
    }

    var searchQuery by remember { mutableStateOf("") }
    fun <T> List<T>.match(matcher: (T) -> Boolean) = if (searchQuery.isBlank()) this else filter(matcher)
    val shownTiles = tiles.match {
        it.label.contains(searchQuery, ignoreCase = true) ||
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
    }
    val shownShortcuts = shortcuts.match {
        it.label.contains(searchQuery, ignoreCase = true) ||
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
    }
    val loading = tiles.isEmpty() && shortcuts.isEmpty() && searchQuery.isEmpty()

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

        item {
            Text(
                text = stringResource(
                    R.string.qs_tile_picker_count, shownTiles.size + shownShortcuts.size
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (shownTiles.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.qs_tile_group_tiles),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(shownTiles, key = { "t" + it.packageName + "/" + it.className }) { tile ->
                PickerRow(tile) { onSelected(tile, true) }
            }
        }

        if (shownShortcuts.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.qs_tile_group_shortcuts),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(shownShortcuts, key = { "s" + it.packageName + "/" + it.className + "/" + it.label }) { item ->
                PickerRow(item) { onSelected(item, false) }
            }
        }
    }
}

@Composable
private fun PickerRow(item: QsTileInfo, onClick: () -> Unit) {
    // 摘要=应用名 · 包名 · 类短名，>40 字符截断（§UI 规范：长文本摘要统一观感）
    val raw = "${item.appLabel} · ${item.packageName} · ${item.className.substringAfterLast('.')}"
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
