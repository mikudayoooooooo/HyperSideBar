package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.PrefsFiles
import com.lsp.hypersidebar.util.RelayToken
import com.lsp.hypersidebar.util.ShortcutRootCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
 *
 * 防伪（2026-11-02 审查 P2 定案）：回包一律经 [ShortcutRelayReceiver] 的**令牌闸**
 * 后再调本 object 的 [onReply]——本文件**不再**自带接收器（原动态接收器是同一落盘
 * 路径的第二入口，且当时按"发送方包名"判真伪；该 API 在本机 OS4/API 36 上对跨应用
 * 广播实测返回 null ⇒ 恒 fail-open，已废弃，见 ShortcutRelayReceiver.handleReply）。
 */
object ManifestShortcutsBridge {

    var shortcuts by mutableStateOf(emptyList<QsTileInfo>())
        private set

    @Volatile private var registered = false

    private const val LOCAL_PREFS = PrefsFiles.APP_LOCAL
    private const val CACHE_KEY = "manifest_shortcuts_cache"

    /** 解析上限（条）：正常清单百级，截断只为拦住病态载荷。 */
    private const val MAX_MANIFEST_ENTRIES = 2000

    /** 只做「载入上次缓存」；应答由 manifest 接收器统一入口送入（幂等）。 */
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
        }.onFailure { Log.w(TAG, "manifest shortcuts cache load failed: ${it.message}") }
    }

    /** 向 launcher 进程请求最新清单（后台查询+应答，到达后 shortcuts 状态驱动重组）。 */
    fun request(context: Context) {
        runCatching {
            // 显式定向 launcher 桥宿主（唯一接收方）：请求携带令牌，隐式发送可被
            // 任意 App 截收（0912 审查，与 sendSync 同类修复）
            val intent = Intent(PrefKeys.MANIFEST_SHORTCUTS_REQUEST)
                .setPackage(HostPackages.HOME)
            RelayToken.attach(intent, RelayToken.current())
            context.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "manifest bridge request failed: ${it.message}") }
        // :ui 直查（2026-10-25 免 root 化）：OS4 上 launcher 桥结构性缺席，:ui（uid 1000）
        // 若能过 LauncherApps 的"默认桌面"检查就免 root 拿到清单；失败静默（:ui 侧已打
        // 判词日志）⇒ 下方 1.5s root 兜底照旧。应答复用 MANIFEST_SHORTCUTS_REPLY 通路
        //（ShortcutRelayReceiver → onReply），本函数零新状态。
        runCatching {
            val uiIntent = Intent(PrefKeys.SHORTCUT_QUERY_UI_ACTION)
                .setPackage(HostPackages.UI_HOST)
            RelayToken.attach(uiIntent, RelayToken.current())
            context.sendBroadcast(uiIntent)
        }.onFailure { Log.w(TAG, "shortcut :ui query request failed: ${it.message}") }
        scheduleRootFallback(context)
    }

    /** 桥应答去重 + 防重入（request 会被重组反复调）。 */
    @Volatile private var fallbackScheduled = false

    /**
     * OS4 兜底（2026-10-20）：桌面被 Rust 重写、无 Java 层 ⇒ 桥结构性缺席，广播无人
     * 应答 ⇒ 延迟 [ROOT_FALLBACK_DELAY_MS] 后清单仍为空就走 root 枚举
     * （[ShortcutRootCatalog.refresh]，marble 真机已验证的原语）。OS2/OS3 桥先到，
     * 兜底自动跳过，零回归。
     */
    private const val ROOT_FALLBACK_DELAY_MS = 1_500L

    private fun scheduleRootFallback(context: Context) {
        if (fallbackScheduled) return
        fallbackScheduled = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            fallbackScheduled = false
            if (shortcuts.isEmpty()) {
                Thread {
                    runCatching { ShortcutRootCatalog.refresh(context) }
                        .onFailure { Log.w(TAG, "root catalog refresh failed: ${it.message}") }
                }.start()
            }
        }, ROOT_FALLBACK_DELAY_MS)
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
        // 上限 2000 条（2026-11-02 审查 P2）：回包来自 hook 侧，正常清单是百级；
        // 截断只为挡住病态/投毒载荷把选择器撑爆
        return (0 until minOf(arr.length(), MAX_MANIFEST_ENTRIES)).mapNotNull { i ->
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
                appLabel = ob.optString("a").ifEmpty { pkg },
                shortcutId = sid
            )
        }
    }
}

/**
 * 快捷开关/应用快捷方式三级选择器面板（页模式与添加 sheet 共用）。
 * 磁贴选中→QS_TILE（SystemUI hook 数据层直点，无须 root、无须固定在控制中心）；
 * 快捷方式选中→COMPONENT（am start 直启目标 activity）。动态 shortcut 因平台约束（B2 归档）不在列。
 *
 * @param handleSystemBack 页模式接管系统返回（L3→L2 一级）；sheet 内 false
 * @param bar 顶栏刷新桥（仅页模式；sheet 内 null = 每次打开天然重扫）
 */
@Composable
internal fun QsTilePickerPane(
    onSelected: (item: QsTileInfo, isTile: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
    bar: RefreshBar? = null,
    /** sheet 内为 false：OverlayBottomSheet 自己处理嵌套滚动 */
    enablePullToRefresh: Boolean = true
) {
    val context = LocalContext.current

    var refreshToken by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    /** 失败自动重试只允许一次（跨跑次存活，避免异常时循环） */
    var autoRetried by remember { mutableStateOf(false) }

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
        key2 = bridgedShortcuts + refreshToken.toString()
    ) {
        loading = true
        loadFailed = false
        try {
            value = withContext(Dispatchers.IO) {
                buildTileApps(context, bridgedShortcuts.ifEmpty { loadManifestShortcuts(context) })
            }
            // 成功优先于任何被取代跑次留下的陈旧失败标记
            if (isActive) loadFailed = false
        } catch (c: CancellationException) {
            // 初次打开时 ManifestShortcutsBridge 的应答会改 key2 ⇒ 上一轮被取消；
            // 取消不是失败（旧代码当异常捕获 ⇒ 数据已到位却显示「加载失败」）
            throw c
        } catch (t: Throwable) {
            Log.w("HsQsTilePicker", "buildTileApps failed", t)
            // 被取代的一轮不许写失败态（同 loading）：否则新一轮已填好列表、界面仍挂失败卡
            if (isActive) loadFailed = true
        } finally {
            if (isActive) {
                // 完成即收指示器（原实现用 apps.size 变化判断，等长结果时指示器一闪即灭）
                loading = false
                refreshing = false
            }
        }
        // 失败自动重试一次：manifest 桥首答与本地扫描可能同时发生，失败多半是这一次竞态；
        // 上限一次，避免异常时循环
        if (isActive && loadFailed && !autoRetried) {
            autoRetried = true
            Log.w("HsQsTilePicker", "first load failed, auto retry once")
            refreshToken++
        }
        if (refreshToken > 0) ManifestShortcutsBridge.request(context)
    }

    SideEffect { bar?.onRefresh = { refreshing = true; refreshToken++ } }
    DisposableEffect(Unit) { onDispose { bar?.onRefresh = null } }

    // 顶部同级类型 Tab（用户 2026-09-05：随时切换）+ 单级返回栈：L3 → L2 → 关闭选择器
    var selectedGroup by remember { mutableStateOf(true) } // true=磁贴 false=快捷方式
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activity = LocalContext.current as? ComponentActivity
    // 内部返回栈只到 L2（某应用的条目列表）；L1 的返回交给 nav3 NavDisplay 弹栈
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
        // 类型 Tab（同级，随时切换；切换即回到该类型的应用列表）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsChip(
                    text = stringResource(R.string.qs_tile_group_tiles),
                    selected = selectedGroup,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedGroup = true
                        selectedPackage = null
                    }
                )
                SettingsChip(
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

        // 状态卡只在"没有列表可看"时出现（2026-10-31）：旧门 `loading || loadFailed || apps.isEmpty()`
        // 会在**已有列表**时再插一张卡——首开竞态下用户看到「加载失败」压在已经加载出来的列表上方。
        if (apps.isEmpty() && (loading || loadFailed)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = stringResource(
                                when {
                                    loading -> R.string.qs_tile_picker_loading
                                    loadFailed -> R.string.picker_failed
                                    else -> R.string.picker_empty
                                }
                            ),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (loadFailed) {
                            TextButton(
                                text = stringResource(R.string.picker_retry),
                                onClick = { refreshing = true; refreshToken++ },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
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
    if (enablePullToRefresh) {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; refreshToken++ },
            topAppBarScrollBehavior = LocalTopAppBarScrollBehavior.current,
            modifier = modifier.fillMaxSize()
        ) { list() }
    } else {
        Box(modifier.fillMaxSize()) { list() }
    }
}

/** 页模式薄壳：顶栏刷新桥（返回行统一由 MainScreen 顶栏承担）。 */
@Composable
internal fun QsTilePickerPage(
    onSelected: (item: QsTileInfo, isTile: Boolean) -> Unit,
    bar: RefreshBar? = null
) {
    QsTilePickerPane(
        onSelected = onSelected,
        handleSystemBack = true,
        bar = bar
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
