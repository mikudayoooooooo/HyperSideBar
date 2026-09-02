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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.AppIconImage
import com.lsp.hypersidebar.ui.fan.FanAppInfo
import com.lsp.hypersidebar.ui.fan.rememberAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

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
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedApps by remember(prefs, prefsKey) {
        mutableStateOf(prefs.getStringSet(prefsKey, emptySet()).orEmpty().toSet())
    }
    // 已选顺序（拖动排序，§2.4）：CUSTOM_APPS_ORDER JSON 数组为权威；
    // 顺序键缺失/不完整时（旧数据迁移——升级前勾选的应用不在键里）把缺失项
    // 追加尾部，保证已选组完整可见，否则已选应用会在列表中整体消失
    var selectedOrder by remember(prefs, prefsKey) {
        val selected = prefs.getStringSet(prefsKey, emptySet()).orEmpty().toSet()
        val stored = loadSelectedOrder(prefs)
        mutableStateOf(stored.filter { it in selected } + (selected - stored.toSet()))
    }

    fun persistSelection() {
        prefs.savePref(prefsKey, selectedApps)
        prefs.savePref(
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
        key1 = context.applicationContext
    ) {
        if (cachedApps != null) return@produceState  // 会话缓存已随 initialValue 命中
        val appContext = context.applicationContext

        // ① 读"之前的"：模块本地准入列表缓存（:ui 上次回带后落库），秒开且不依赖 :ui 存活。
        // 准入数据源（PRD §7.3.3"无小窗资格的应用在数据源层面即不展示"）取代原 PM 全列表
        val cached = readCachedSuggestions(prefs)
        if (cached.isNotEmpty()) {
            val items = withContext(Dispatchers.IO) {
                withMissingPinned(appContext, prefs, prefsKey, loadAdmissionItems(appContext, cached))
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
                withMissingPinned(appContext, prefs, prefsKey, loadAdmissionItems(appContext, fresh))
            }
            persistCachedSuggestions(prefs, fresh)
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { }

        Text(
            text = stringResource(R.string.selected_apps_count, selectedApps.size),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
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

@Composable
private fun AppList(
    apps: List<AppItem>,
    showGroups: Boolean,
    selectedApps: Set<String>,
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
    val listState = rememberLazyListState()
    val dragState = rememberDragReorderState(
        listState = listState,
        onMoveByKey = { fromKey, toKey ->
            if (fromKey is String && toKey is String) onReorder(fromKey, toKey)
        },
        onDragFinished = onReorderFinished
    )
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
    ) {
        items(apps.size, key = { apps[it].packageName }) { index ->
            val app = apps[index]
            if (showGroups) {
                when {
                    index == 0 && selectedCount > 0 ->
                        SmallTitle(text = stringResource(R.string.selected_apps_group, selectedCount))
                    index == selectedCount ->
                        SmallTitle(
                            text = stringResource(
                                if (app.isSystem) R.string.system_apps else R.string.user_apps
                            )
                        )
                    index == firstSystemUnselected ->
                        SmallTitle(text = stringResource(R.string.system_apps))
                }
            }
            AppSelectionRow(
                app = app,
                isChecked = app.packageName in selectedApps,
                onToggle = { onToggle(app.packageName) },
                dragState = dragState,
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: AppItem,
    isChecked: Boolean,
    onToggle: () -> Unit,
    dragState: DragReorderState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appInfo = remember(app.packageName, app.label) {
        FanAppInfo(packageName = app.packageName, appName = app.label)
    }
    val (drawable, fallbackColor) = rememberAppIcon(context, appInfo)
    val colors = currentFanThemeColors()

    BasicComponent(
        title = app.label,
        summary = app.packageName,
        startAction = {
            AppIconImage(
                drawable = drawable,
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
            // 拖动排序手柄：仅已选项可拖（未选项顺序由分组规则决定）
            if (isChecked) {
                Icon(
                    imageVector = MiuixIcons.Sort,
                    contentDescription = stringResource(R.string.shortcut_drag_handle),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp)
                        .dragReorderHandle(dragState, app.packageName)
                )
            }
        },
        onClick = onToggle,
        modifier = modifier.dragReorderItem(dragState, app.packageName)
    )
}

private fun loadInstalledApps(context: Context): List<AppItem> {
    val packageManager = context.packageManager
    val installed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0)
    }
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
        setPackage("com.miui.securitycenter")
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
