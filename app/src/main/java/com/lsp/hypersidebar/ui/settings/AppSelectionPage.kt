package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.savePref
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.AppIconImage
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.ui.fan.rememberAppIcon
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.RemotePrefsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class AppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean
)

private sealed interface AppLoadState {
    data object Loading : AppLoadState
    data class Loaded(val apps: List<AppItem>) : AppLoadState
    data object Failed : AppLoadState
}

@Volatile
private var cachedApps: List<AppItem>? = null

@Composable
internal fun AppSelectionPage(
    prefs: SharedPreferences,
    prefsKey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // D1 残留（同 SettingsPage:52）：nav3 entry 可能固化绑定前的本地空壳 prefs，
    // 读写统一切到 bridge 真源（remote 绑定后 Compose state 强制重组切读取源）
    val effectivePrefs = RemotePrefsBridge.prefs ?: prefs
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    // 数据源在 App 之外（:ui 准入列表 + PM）⇒ 支持顶栏刷新与下拉刷新
    var refreshToken by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedApps by remember(effectivePrefs, prefsKey) {
        mutableStateOf(effectivePrefs.getStringSet(prefsKey, emptySet()).orEmpty().toSet())
    }
    // 已选顺序（拖动排序，§2.4）：CUSTOM_APPS_ORDER JSON 数组为权威；
    // 顺序键缺失/不完整时（旧数据迁移——升级前勾选的应用不在键里）把缺失项
    // 追加尾部，保证已选组完整可见，否则已选应用会在列表中整体消失
    var selectedOrder by remember(effectivePrefs, prefsKey) {
        val selected = effectivePrefs.getStringSet(prefsKey, emptySet()).orEmpty().toSet()
        val stored = loadSelectedOrder(effectivePrefs)
        mutableStateOf(stored.filter { it in selected } + (selected - stored.toSet()))
    }

    fun persistSelection() {
        effectivePrefs.savePref(prefsKey, selectedApps)
        effectivePrefs.savePref(
            PrefKeys.CUSTOM_APPS_ORDER,
            org.json.JSONArray(selectedOrder.filter { it in selectedApps }).toString()
        )
    }

    fun toggle(pkg: String) {
        selectedApps = selectedApps.toMutableSet().apply {
            if (!remove(pkg)) add(pkg)
        }.toSet()
        selectedOrder = if (pkg in selectedOrder) {
            selectedOrder - pkg
        } else {
            selectedOrder + pkg
        }
        persistSelection()
    }

    val loadState by produceState<AppLoadState>(
        initialValue = cachedApps?.let(AppLoadState::Loaded) ?: AppLoadState.Loading,
        key1 = context.applicationContext,
        key2 = refreshToken
    ) {
        try {
            // 仅在「没有刷新请求」时才允许命中会话缓存；否则刷新永远拿不到新数据
            if (cachedApps != null && refreshToken == 0) return@produceState
            val appContext = context.applicationContext

            // ① 读"之前的"：模块本地准入列表缓存（:ui 上次回带后落库），秒开且不依赖 :ui 存活。
            // 准入数据源（PRD §7.3.3"无小窗资格的应用在数据源层面即不展示"）取代原 PM 全列表
            val cached = readCachedSuggestions(effectivePrefs)
            if (cached.isNotEmpty()) {
                val items = withContext(Dispatchers.IO) {
                    withMissingPinned(appContext, effectivePrefs, prefsKey, loadAdmissionItems(appContext, cached))
                }
                cachedApps = items
                value = AppLoadState.Loaded(items)
            }

            // ② 有序广播向 :ui 刷新（探针同款信道，PRD 准入列表权威源）：应答非空且与缓存
            // 不同才重排 + 落库，页面无感更新
            val fresh: List<String>? = suspendCoroutine { cont ->
                requestSuggestionsFromUi(appContext) { cont.resume(it) }
            }
            if (fresh != null && fresh != cached) {
                val items = withContext(Dispatchers.IO) {
                    withMissingPinned(appContext, effectivePrefs, prefsKey, loadAdmissionItems(appContext, fresh))
                }
                persistCachedSuggestions(effectivePrefs, fresh)
                cachedApps = items
                value = AppLoadState.Loaded(items)
                return@produceState
            }
            if (cached.isNotEmpty()) return@produceState

            // ③ 兜底：无缓存且 :ui 无应答/空缓存 → PM 全列表（模块进程被 blocklist 拒绝调
            // 准入 API，原现状路径；此时扇形端也必然无准入数据，此处固定项不会被消费）
            value = runCatching {
                withContext(Dispatchers.IO) { loadInstalledApps(appContext) }
            }.fold(
                onSuccess = { apps ->
                    cachedApps = apps
                    AppLoadState.Loaded(apps)
                },
                onFailure = { AppLoadState.Failed }
            )
        } finally {
            // 扫描结束即收指示器：原实现看 loadState 是否变 Loaded，但新数据同尺寸时
            // produceState 不写新值 ⇒ 指示器挂在旧 Loaded 上立刻熄灭（"刷新没反应"）
            refreshing = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                InputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { searchExpanded = false },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    label = stringResource(R.string.search_apps_hint)
                )
            },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsDimens.ScreenInset, vertical = 8.dp)
        ) { }

        Text(
            text = stringResource(R.string.selected_apps_count, selectedApps.size),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SettingsDimens.TitleInset, vertical = 4.dp)
        )

        when (val state = loadState) {
            AppLoadState.Loading -> LoadingApps()
            AppLoadState.Failed -> MessageState(stringResource(R.string.apps_load_failed))
            is AppLoadState.Loaded -> {
                // 已选优先（§2.4）：选中项按 CUSTOM_APPS_ORDER 置顶成组，
                // 未选保持 user→system 原排序；搜索结果同规则
                val filteredApps = remember(searchQuery, state.apps, selectedApps, selectedOrder) {
                    val base = if (searchQuery.isBlank()) {
                        state.apps
                    } else {
                        state.apps.filter { app ->
                            app.label.contains(searchQuery, ignoreCase = true) ||
                                app.packageName.contains(searchQuery, ignoreCase = true)
                        }
                    }
                    val byPkg = base.associateBy { it.packageName }
                    val orderedSelected = selectedOrder.mapNotNull { byPkg[it] }
                    val rest = base.filter { it.packageName !in selectedApps }
                    orderedSelected + rest
                }
                AppList(
                    apps = filteredApps,
                    showGroups = searchQuery.isBlank(),
                    selectedApps = selectedApps,
                    refreshing = refreshing,
                    onRefresh = { refreshing = true; refreshToken++ },
                    onToggle = { toggle(it) },
                    onReorder = { fromPkg, toPkg ->
                        val from = selectedOrder.indexOf(fromPkg)
                        val to = selectedOrder.indexOf(toPkg)
                        if (from >= 0 && to >= 0) {
                            selectedOrder = selectedOrder.toMutableList().apply { add(to, removeAt(from)) }
                        }
                    },
                    onReorderFinished = { persistSelection() }
                )
            }
        }
    }
}

private fun loadSelectedOrder(prefs: SharedPreferences): List<String> {
    val json = runCatching { prefs.getString(PrefKeys.CUSTOM_APPS_ORDER, null) }.getOrNull()
        ?: return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.optString(it) }
    }.getOrDefault(emptyList())
}

@Composable
private fun LoadingApps() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.loading_apps),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun MessageState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/** 列表单元格：分组标题与数据行平级成项——标题原先嵌在数据项内部，会被被拖行带着一起走。 */
private sealed interface AppCell {
    val key: String
}

private data class HeaderCell(val text: String, override val key: String) : AppCell

private data class RowCell(val app: AppItem) : AppCell {
    override val key get() = app.packageName
}

@Composable
private fun AppList(
    apps: List<AppItem>,
    showGroups: Boolean,
    selectedApps: Set<String>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onReorder: (fromPkg: String, toPkg: String) -> Unit,
    onReorderFinished: () -> Unit
) {
    if (apps.isEmpty()) {
        MessageState(stringResource(R.string.no_apps_found))
        return
    }
    // 已选优先分组：[已选 N] → 未选用户应用 → 系统应用；已选组内可拖动排序
    val selectedCount = remember(apps, selectedApps) {
        apps.count { it.packageName in selectedApps }
    }
    val firstSystemUnselected = remember(apps, selectedApps) {
        apps.drop(selectedCount).indexOfFirst { it.isSystem }
            .let { if (it >= 0) it + selectedCount else -1 }
    }
    val cells = buildAppCells(
        apps = apps,
        showGroups = showGroups,
        selectedCount = selectedCount,
        firstSystemUnselected = firstSystemUnselected,
        selectedGroupTitle = stringResource(R.string.selected_apps_group, selectedCount),
        userAppsTitle = stringResource(R.string.user_apps),
        systemAppsTitle = stringResource(R.string.system_apps)
    )
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        scrollThresholdPadding = WindowInsets.systemBars.asPaddingValues(),
        // 库默认触发带太窄（贴屏边才滚），放宽到 120dp——手柄拖拽时手指够不到屏幕边缘
        scrollThreshold = 120.dp
    ) { from, to ->
        val fromPkg = from.key as? String
        val toPkg = to.key as? String
        // 换位只在已选行之间发生：标题格与未选行（顺序由分组规则决定）不作目标
        if (fromPkg != null && toPkg != null && toPkg in selectedApps) onReorder(fromPkg, toPkg)
    }
    val haptics = LocalHapticFeedback.current
    PullToRefresh(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        topAppBarScrollBehavior = LocalTopAppBarScrollBehavior.current,
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = PaddingValues(
            start = SettingsDimens.ScreenInset,
            end = SettingsDimens.ScreenInset
        )
    ) {
        items(cells, key = { it.key }) { cell ->
            when (cell) {
                is HeaderCell -> SectionTitle(text = cell.text)
                is RowCell -> {
                    val pkg = cell.app.packageName
                    val checked = pkg in selectedApps
                    if (!checked) {
                        AppSelectionRow(app = cell.app, isChecked = false, onToggle = { onToggle(pkg) })
                    } else {
                        ReorderableItem(
                            state = reorderState,
                            key = cell.key,
                            animateItemModifier = Modifier.animateItem()
                        ) { isDragging ->
                            // 拖动中的抬升感：行无卡底，阴影不可见，用微放大表达"浮起"
                            val scale by animateFloatAsState(
                                if (isDragging) 1.03f else 1f, label = "appDragScale"
                            )
                            AppSelectionRow(
                                app = cell.app,
                                isChecked = true,
                                onToggle = { onToggle(pkg) },
                                handleModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = onReorderFinished
                                ),
                                modifier = Modifier.graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

private fun buildAppCells(
    apps: List<AppItem>,
    showGroups: Boolean,
    selectedCount: Int,
    firstSystemUnselected: Int,
    selectedGroupTitle: String,
    userAppsTitle: String,
    systemAppsTitle: String
): List<AppCell> {
    val cells = ArrayList<AppCell>(apps.size + 2)
    apps.forEachIndexed { index, app ->
        if (showGroups) {
            // 与旧内联版同规则：when 级联，一个索引位最多一个小标题
            val header = when {
                index == 0 && selectedCount > 0 -> selectedGroupTitle to "header_selected"
                index == selectedCount ->
                    (if (app.isSystem) systemAppsTitle else userAppsTitle) to "header_unselected"
                index == firstSystemUnselected -> systemAppsTitle to "header_system"
                else -> null
            }
            if (header != null) cells += HeaderCell(header.first, header.second)
        }
        cells += RowCell(app)
    }
    return cells
}

@Composable
private fun AppSelectionRow(
    app: AppItem,
    isChecked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appInfo = remember(app.packageName, app.label) {
        FanAppInfo(packageName = app.packageName, appName = app.label)
    }
    val (bitmap, fallbackColor) = rememberAppIcon(context, appInfo)
    val colors = currentFanThemeColors()

    BasicComponent(
        title = app.label,
        summary = app.packageName,
        startAction = {
            AppIconImage(
                bitmap = bitmap,
                fallbackColor = fallbackColor,
                appName = app.label,
                size = 36f,
                colors = colors
            )
        },
        endActions = {
            Checkbox(
                state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle
            )
            // 拖动排序手柄：48dp 触摸热区（Android 最小触摸目标的建议值；图标视觉仍 24dp）。
            // 尾槽**恒存在**、只在已选态挂手柄——否则勾选瞬间尾槽变宽，Checkbox 会左跳
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(48.dp)
                    .then(if (isChecked) handleModifier else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Icon(
                        imageVector = MiuixIcons.Sort,
                        contentDescription = stringResource(R.string.shortcut_drag_handle),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        onClick = onToggle,
        modifier = modifier
    )
}

private fun loadInstalledApps(context: Context): List<AppItem> {
    val packageManager = context.packageManager
    // minSdk 33：ApplicationInfoFlags 版恒定可用，无版本分支
    val installed = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    return installed
        .asSequence()
        .filter { it.enabled }
        .map { info ->
            AppItem(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            )
        }
        .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
        .toList()
}

/** 读模块本地准入列表缓存（"读之前的"主路径）；缺失/损坏返回空 */
private fun readCachedSuggestions(prefs: SharedPreferences): List<String> =
    runCatching {
        prefs.getString(PrefKeys.CACHED_SUGGESTIONS, null)?.let { json ->
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        }
    }.getOrNull().orEmpty()

/** 准入列表落库（:ui 上次回带结果；与 DataLoader 落盘同格式），空列表不覆盖 */
private fun persistCachedSuggestions(prefs: SharedPreferences, pkgs: List<String>) {
    if (pkgs.isEmpty()) return
    runCatching { prefs.savePref(PrefKeys.CACHED_SUGGESTIONS, org.json.JSONArray(pkgs).toString()) }
}

/** 准入包名 → AppItem（label 走 PM；已卸载的剔除，其固定引用由 withMissingPinned 保住） */
private fun loadAdmissionItems(context: Context, pkgs: List<String>): List<AppItem> {
    val packageManager = context.packageManager
    return pkgs.mapNotNull { pkg ->
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            AppItem(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = pkg,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            )
        }.getOrNull()
    }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
}

/** 已固定但不在准入列表里的项仍要可见（用户资产；资格变化只影响启动，PRD §9.4 toast 兜底） */
private fun withMissingPinned(
    context: Context,
    prefs: SharedPreferences,
    prefsKey: String,
    items: List<AppItem>
): List<AppItem> {
    val missing = prefs.getStringSet(prefsKey, emptySet()).orEmpty() -
        items.map { it.packageName }.toSet()
    if (missing.isEmpty()) return items
    val packageManager = context.packageManager
    val extras = missing.map { pkg ->
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            AppItem(
                label = packageManager.getApplicationLabel(info).toString(),
                packageName = pkg,
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            )
        }.getOrNull() ?: AppItem(label = pkg, packageName = pkg, isSystem = true)
    }
    return items + extras  // 已选置顶由 UI 层 selectedOrder 排序处理，这里只保证存在
}

/** 有序广播向 :ui 请求准入列表（探针同款信道）；null = 无应答（:ui 死/未激活）或空缓存 */
private fun requestSuggestionsFromUi(context: Context, onResult: (List<String>?) -> Unit) {
    val intent = Intent(PrefKeys.ACTION_REQUEST_SUGGESTIONS).apply {
        setPackage(HostPackages.UI_HOST)
        // 跨进程防伪令牌（:ui 侧 FreeformRelayHook 校验）
        RelayToken.attach(this, RelayToken.current())
    }
    runCatching {
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val list = runCatching {
                        getResultExtras(true).getStringArrayList(PrefKeys.EXTRA_SUGGESTION_LIST)
                    }.getOrNull().orEmpty()
                    onResult(list.ifEmpty { null })
                }
            },
            Handler(Looper.getMainLooper()),
            0, null, null
        )
    }.onFailure { onResult(null) }
}
