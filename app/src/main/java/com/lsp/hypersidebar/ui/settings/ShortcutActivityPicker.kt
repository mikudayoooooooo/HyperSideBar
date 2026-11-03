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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val TAG = "ShortcutSettings"

data class ComponentInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val appLabel: String,
    val exported: Boolean,
    val isService: Boolean,
    val isShortcutTarget: Boolean = false,
    val isQsTile: Boolean = false
)

private data class AppInfo(
    val packageName: String,
    val appLabel: String,
    val components: List<ComponentInfo>
)

/** 导出三态过滤（Step 7：替换原「只看发现目标」开关）。 */
private enum class ExportFilter { ALL, EXPORTED, UNEXPORTED }

/**
 * 组件/页面选择器面板：页模式（编辑页「选择应用」）与添加 sheet 内共用同一实现。
 *
 * @param handleSystemBack 是否接管系统返回（页模式 true；sheet 内 false，交给 sheet）
 * @param bar 顶栏刷新桥（只有页模式传；sheet 内 null = 每次打开重新组合即重扫）
 */
@Composable
internal fun ActivityPickerPane(
    onSelected: (packageName: String, activityName: String, label: String, isQsTile: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
    bar: RefreshBar? = null,
    /** sheet 内为 false：OverlayBottomSheet 自己处理嵌套滚动，再套 PullToRefresh 会抢手势 */
    enablePullToRefresh: Boolean = true
) {
    val context = LocalContext.current

    var refreshToken by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    val apps by produceState<List<AppInfo>>(
        initialValue = emptyList(),
        key1 = context.applicationContext,
        key2 = refreshToken
    ) {
        loading = true
        loadFailed = false
        try {
            value = withContext(Dispatchers.IO) {
                loadAppsByPackage(context)
            }
            // 成功优先于任何被取代跑次留下的陈旧失败标记
            if (isActive) loadFailed = false
        } catch (c: CancellationException) {
            // produceState 键变化（或场景切换）会取消上一轮：取消不是失败。旧代码把它当异常
            // 捕获 ⇒ 新一轮已经把数据填上，界面却仍挂着「加载失败」（2026-10-30 用户走查）。
            throw c
        } catch (t: Throwable) {
            Log.w("HsActivityPicker", "loadAppsByPackage failed", t)
            // 被取代的那一轮不许写失败态（同 loading 的理由）：旧代码在此无条件置 true，
            // 新一轮已经成功填好列表，屏幕上却挂出「加载失败」——用户症状「首次打开必报失败」
            if (isActive) loadFailed = true
        } finally {
            // 取消的那一轮的 finally 会跑在**新一轮开始之后**，写 loading/refreshing
            // 等于把新轮次的「加载中」抹掉 ⇒ 只有仍存活的轮次才收指示器
            if (isActive) {
                // 完成即收指示器：原实现用「旧列表大小是否变化」判断，新数据同尺寸时指示器一闪即灭
                loading = false
                refreshing = false
            }
        }
    }

    SideEffect { bar?.onRefresh = { refreshing = true; refreshToken++ } }
    DisposableEffect(Unit) { onDispose { bar?.onRefresh = null } }

    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activity = LocalContext.current as? ComponentActivity

    // 选择器内部返回栈：L2（某应用的组件列表）→ L1（应用列表）。
    // L1 的返回交给 nav3 NavDisplay 自己弹栈——页面内不再画第二个返回行/箭头。
    if (handleSystemBack) {
        DisposableEffect(activity, selectedPackage) {
            val callback = object : OnBackPressedCallback(selectedPackage != null) {
                override fun handleOnBackPressed() {
                    selectedPackage = null
                }
            }
            activity?.onBackPressedDispatcher?.addCallback(callback)
            onDispose { callback.remove() }
        }
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(modifier.fillMaxSize()) {
        when (selectedPackage) {
            null -> AppList(
                apps = filteredApps,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onSelectApp = { selectedPackage = it },
                refreshing = refreshing,
                onRefresh = { refreshing = true; refreshToken++ },
                loading = loading,
                loadFailed = loadFailed,
                enablePullToRefresh = enablePullToRefresh
            )
            else -> {
                val app = apps.find { it.packageName == selectedPackage }
                if (app != null) {
                    ActivityList(
                        app = app,
                        onSelected = onSelected,
                        onBack = { selectedPackage = null }
                    )
                }
            }
        }
    }
}

/** 页模式薄壳：顶栏刷新桥（返回行统一由 MainScreen 顶栏承担）。 */
@Composable
internal fun ActivityPickerPage(
    onSelected: (packageName: String, activityName: String, label: String, isQsTile: Boolean) -> Unit,
    bar: RefreshBar? = null
) {
    ActivityPickerPane(
        onSelected = onSelected,
        handleSystemBack = true,
        bar = bar
    )
}

@Composable
private fun AppList(
    apps: List<AppInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    loading: Boolean,
    loadFailed: Boolean,
    enablePullToRefresh: Boolean = true
) {
    val list: @Composable () -> Unit = {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic(),
            contentPadding = PaddingValues(
                start = SettingsDimens.ScreenInset,
                end = SettingsDimens.ScreenInset,
                top = SettingsDimens.ListTop,
                bottom = SettingsDimens.ListBottom
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        label = stringResource(R.string.activity_picker_search),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            if (apps.isEmpty() && searchQuery.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = stringResource(
                                    when {
                                        loading -> R.string.activity_picker_loading
                                        loadFailed -> R.string.picker_failed
                                        else -> R.string.picker_empty
                                    }
                                ),
                                modifier = Modifier.padding(SettingsDimens.CardInner),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            if (loadFailed) {
                                TextButton(
                                    text = stringResource(R.string.picker_retry),
                                    onClick = onRefresh,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.activity_picker_count, apps.size),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            items(apps, key = { it.packageName }) { app ->
                ArrowPreference(
                    title = app.appLabel,
                    summary = stringResource(R.string.shortcut_component_count, app.packageName, app.components.size),
                    startAction = { SettingsAppIcon(packageName = app.packageName, appName = app.appLabel, size = 28f) },
                    onClick = { onSelectApp(app.packageName) }
                )
            }
        }
    }
    if (enablePullToRefresh) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            topAppBarScrollBehavior = LocalTopAppBarScrollBehavior.current,
            modifier = Modifier.fillMaxSize()
        ) { list() }
    } else {
        list()
    }
}

@Composable
private fun ActivityList(
    app: AppInfo,
    onSelected: (packageName: String, activityName: String, label: String, isQsTile: Boolean) -> Unit,
    onBack: () -> Unit
) {
    // 导出三态过滤（用户定案）：互斥恒有一项选中，替换原「只看发现目标」开关；
    // 发现目标仍靠行内 [快捷]/[QS]/🔒 标签表达
    var filter by remember(app.packageName) { mutableStateOf(ExportFilter.ALL) }
    // 组件级搜索（2026-11-03）：314 个组件的应用只能滚，补一个与列表同款的输入框
    var query by remember(app.packageName) { mutableStateOf("") }
    val shown = app.components.filter {
        val exportOk = when (filter) {
            ExportFilter.ALL -> true
            ExportFilter.EXPORTED -> it.exported
            ExportFilter.UNEXPORTED -> !it.exported
        }
        val q = query.trim()
        exportOk && (
            q.isEmpty() ||
                it.label.contains(q, ignoreCase = true) ||
                it.className.contains(q, ignoreCase = true)
            )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .settingsScroll()
                .scrollEndHaptic(),
            // end 留出右侧过滤图标宽度，末行不被遮
            contentPadding = PaddingValues(
                start = SettingsDimens.ScreenInset,
                end = 48.dp,
                top = SettingsDimens.ListTop,
                bottom = SettingsDimens.ListBottom
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                BasicComponent(
                    title = app.appLabel,
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
                        value = query,
                        onValueChange = { query = it },
                        label = stringResource(R.string.activity_picker_search_component),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item {
                Text(
                    text = stringResource(
                        R.string.shortcut_components_info,
                        app.components.size,
                        app.components.count { !it.exported }
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            items(shown, key = { it.className + (if (it.isService) "#s" else "#a") }) { info ->
                val typeTag = if (info.isService) "[S] " else ""
                val quickTag = if (info.isShortcutTarget) "[快捷] " else ""
                val qsTag = if (info.isQsTile) "[QS] " else ""
                val lockTag = if (!info.exported) " 🔒" else ""
                ArrowPreference(
                    title = quickTag + qsTag + typeTag + info.label.ifEmpty { info.className.substringAfterLast('.') } + lockTag,
                    summary = info.className,
                    onClick = {
                        onSelected(info.packageName, info.className, info.label, info.isQsTile)
                    }
                )
            }
        }

        // 过滤：图标 + 当前筛选文字的胶囊（extended-FAB 外观），点开在它上方弹出选项
        val filterLabelRes = when (filter) {
            ExportFilter.ALL -> R.string.activity_filter_all
            ExportFilter.EXPORTED -> R.string.activity_filter_exported
            ExportFilter.UNEXPORTED -> R.string.activity_filter_unexported
        }
        OverlayIconDropdownMenu(
            entry = DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = stringResource(R.string.activity_filter_all),
                        selected = filter == ExportFilter.ALL,
                        onClick = { filter = ExportFilter.ALL }
                    ),
                    DropdownItem(
                        text = stringResource(R.string.activity_filter_exported),
                        selected = filter == ExportFilter.EXPORTED,
                        onClick = { filter = ExportFilter.EXPORTED }
                    ),
                    DropdownItem(
                        text = stringResource(R.string.activity_filter_unexported),
                        selected = filter == ExportFilter.UNEXPORTED,
                        onClick = { filter = ExportFilter.UNEXPORTED }
                    )
                )
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(SettingsDimens.ScreenInset),
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
            cornerRadius = 16.dp,
            minWidth = 108.dp,
            renderInRootScaffold = false
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Filter,
                    contentDescription = stringResource(R.string.activity_filter_title),
                    tint = if (filter == ExportFilter.ALL) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(filterLabelRes),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (filter == ExportFilter.ALL) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

/**
 * C1 spike / C2 数据源（批次 3）：LauncherApps.getShortcuts(MATCH_MANIFEST) 取全系统
 * 清单静态快捷方式，返回 包名 → 目标 activity 类名集合。只当"发现过滤器"——
 * 启动机制零改动（仍 am start 组件名，root 可拉起非导出目标）。
 * 失败（受限/异常）返回空 map，列表退化为未过滤全量，不影响可用性。
 */
private fun loadShortcutTargetActivities(context: Context): Map<String, Set<String>> =
    runCatching {
        val la = context.getSystemService(LauncherApps::class.java)
            ?: return@runCatching emptyMap<String, Set<String>>()
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val infos = la.getShortcuts(query, Process.myUserHandle()).orEmpty()
        val map = HashMap<String, MutableSet<String>>()
        infos.forEach { si ->
            val cn = si.activity ?: return@forEach
            map.getOrPut(cn.packageName) { HashSet() }.add(cn.className)
        }
        // spike 判定点：可见性/稳定性实证（C1b）
        Log.i(TAG, "ShortcutProbe: manifest=${infos.size} pkgs=${map.size} " +
            "sample=${map.entries.take(3).joinToString { "${it.key}=${it.value.size}" }}")
        map
    }.getOrElse { e ->
        Log.w(TAG, "ShortcutProbe failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyMap()
    }

/**
 * C3（批次 3，用户拍板"只展示扫得到的"）：枚举全系统 TileService
 * （QS_TILE action 的 service），返回 "pkg/类名" 集合。
 * 触发走 root `cmd statusbar click-tile`，磁贴须已加入 QS（spike 实测）。
 */
private fun loadQsTileComponents(context: Context): Set<String> =
    runCatching {
        val infos = context.packageManager.queryIntentServices(
            Intent("android.service.quicksettings.action.QS_TILE"), 0
        ).orEmpty()
        val set = infos.mapNotNull { it.serviceInfo }
            .map { "${it.packageName}/${it.name}" }
            .toSet()
        Log.i(TAG, "QsTileProbe: ${set.size} tile services")
        set
    }.getOrElse { e ->
        Log.w(TAG, "QsTileProbe failed: ${e.javaClass.simpleName}: ${e.message}")
        emptySet()
    }

private fun loadAppsByPackage(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val shortcutTargets = loadShortcutTargetActivities(context)
    val qsTiles = loadQsTileComponents(context)
    val apps = mutableMapOf<String, MutableList<ComponentInfo>>()
    val labels = mutableMapOf<String, String>()

    try {
        val packages = pm.getInstalledPackages(0)
        for (pkg in packages) {
            val pkgName = pkg.packageName ?: continue
            val pkgInfo = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            }.getOrNull() ?: continue

            val targets = shortcutTargets[pkgName].orEmpty()
            val components = mutableListOf<ComponentInfo>()

            // Activities（含非导出）
            pkgInfo.activities?.forEach { ai ->
                components.add(ComponentInfo(
                    packageName = pkgName,
                    className = ai.name,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrNull() ?: "",
                    appLabel = "",
                    exported = ai.exported,
                    isService = false,
                    isShortcutTarget = ai.name in targets
                ))
            }

            // Services（含非导出；QS 磁贴服务单独标注供 C3 选取）
            pkgInfo.services?.forEach { si ->
                components.add(ComponentInfo(
                    packageName = pkgName,
                    className = si.name,
                    label = runCatching { si.loadLabel(pm).toString() }.getOrNull() ?: "",
                    appLabel = "",
                    exported = si.exported,
                    isService = true,
                    isQsTile = "$pkgName/${si.name}" in qsTiles
                ))
            }

            if (components.isNotEmpty()) {
                labels[pkgName] = pkgInfo.applicationInfo?.let {
                    pm.getApplicationLabel(it).toString()
                } ?: pkgName
                apps[pkgName] = components
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "loadAppsByPackage failed", e)
    }

    return apps.map { (pkgName, components) ->
        AppInfo(
            packageName = pkgName,
            appLabel = labels[pkgName] ?: pkgName,
            components = components.sortedWith(
                compareBy(
                    { !it.isShortcutTarget }, { !it.isQsTile },
                    { !it.exported }, { it.isService }, { it.label.ifEmpty { it.className } }
                )
            )
        )
    }.sortedBy { it.appLabel.lowercase(Locale.ROOT) }
}
