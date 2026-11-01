package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.HostPackages
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.anim.AccelerateEasing
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

/**
 * 弹出导航栈顶（空栈为 no-op）。
 *
 * ⚠️ **禁止把这些栈写成 `removeLast()`** —— 2026-09-26 实机崩溃（Android 14）：
 *
 * ```
 * java.lang.NoSuchMethodError: No virtual method removeLast()Ljava/lang/Object;
 *   in class Landroidx/compose/runtime/snapshots/SnapshotStateList;
 *   at MainScreen$lambda$11$0$0$9$0$0$0(MainScreen.kt:347)
 * ```
 *
 * 原因：Kotlin 2.4 把 `MutableList.removeLast()` 解析到 **Java 21 的
 * `SequencedCollection.removeLast()` 成员方法**，而该成员在 Android 上**API 35
 * （Android 15）才存在**。本项目 `minSdk = 33`，于是 Android 13/14 设备一调用
 * 就 `NoSuchMethodError` 崩溃（表现为"添加磁贴快捷方式必崩"，因为该路径
 * 会出栈返回上一页）。API 35+ 正常，所以问题只在旧系统上暴露。
 *
 * `removeAt(lastIndex)` 是 `java.util.List` 自 API 1 起就有的成员，任何版本都安全。
 */
private fun popLast(stack: MutableList<*>) {
    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
}

@Composable
internal fun MainScreen(
    prefs: SharedPreferences,
    service: XposedService?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val settingsRepo = remember(prefs) { SettingsRepository(prefs) }
    val prefsRevision = settingsRepo.revision
    val moduleStatus by produceState<ModuleStatus>(
        initialValue = ModuleStatus.INACTIVE,
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) { moduleStatusOf(service) }
    }

    DisposableEffect(settingsRepo) {
        onDispose { settingsRepo.dispose() }
    }

    // 双根 Tab 各持独立栈：切 Tab = 换栈，旧栈整体保活（切回深度不丢，U0 实测）
    val settingsStack = remember { navBackStackOf(SettingsKey.TabSettings) }
    val aboutStack = remember { navBackStackOf(SettingsKey.TabAbout) }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    val activeStack = if (activeTab == 0) settingsStack else aboutStack

    // 快捷方式列表/组编辑的刷新令牌（2026-10-25 真机反馈：子页保存返回后列表不刷新——
    // nav3 返回时 remember 状态保活，workingList 停留旧值须重进页面）。子页
    // 保存/删除/退出时自增，两页 remember(revision) 重建即重读盘
    var shortcutRevision by remember { mutableStateOf(0) }

    // 组详情顶栏＋桥（添加成员 → 页内选择器）
    val groupEditBar = remember { GroupEditBar() }
    // 快捷方式编辑页顶栏保存桥（✓ 归顶栏）
    val shortcutEditBar = remember { ShortcutEditBar() }
    // 外部数据源三页（App 选择 / 组件选择 / QS 磁贴选择）共用的顶栏刷新桥
    val refreshBar = remember { RefreshBar() }
    // 大标题随滚动收起（页面通过 LocalTopAppBarScrollBehavior 取它去挂 nestedScroll）
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    val selectAppsTitle = stringResource(R.string.select_apps)
    // miuix-nav 的 NavBackStack 元素类型是 NavKey（非泛型），取栈顶要显式收回 SettingsKey
    val currentTitle = when (val top = activeStack.last() as SettingsKey) {
        SettingsKey.TabSettings -> stringResource(R.string.tab_settings)
        SettingsKey.TabAbout -> stringResource(R.string.tab_about)
        is SettingsKey.AppSelection -> top.title
        SettingsKey.ShortcutList -> stringResource(R.string.shortcuts_add_section)
        SettingsKey.ShortcutPicker -> stringResource(R.string.activity_picker_title)
        SettingsKey.QsTilePicker -> stringResource(R.string.qs_tile_picker_title)
        SettingsKey.Logs -> stringResource(R.string.logs_title)
        SettingsKey.Stats -> stringResource(R.string.stats_title)
        SettingsKey.Diagnostics -> stringResource(R.string.diagnostics_entry)
        is SettingsKey.ShortcutEdit -> stringResource(R.string.shortcuts_add_section)
        is SettingsKey.GroupEdit -> stringResource(R.string.group_edit_title)
        SettingsKey.TechInfo -> stringResource(R.string.about_tech_info)
        SettingsKey.InvokeSettings -> stringResource(R.string.invoke_section)
        SettingsKey.FanBackground -> stringResource(R.string.fan_background_section)
    }

    // 批量选择顶栏桥（ShortcutListPage 写 / 本处顶栏读）：HyperOS 批量模式=顶栏变形
    val shortcutSelectionBar = remember { ShortcutSelectionBar() }
    val inBatchSelection = activeStack.lastOrNull() == SettingsKey.ShortcutList &&
        shortcutSelectionBar.active

    // 切页时把大标题收起状态归零：否则从详情页滚到收起态返回后，新页会带着旧的高度偏移
    LaunchedEffect(activeStack.last()) {
        topAppBarScrollBehavior.state.heightOffset = 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (inBatchSelection) {
                    stringResource(R.string.shortcut_selected_count, shortcutSelectionBar.count)
                } else {
                    currentTitle
                },
                // 批量模式取小标题形态：miuix TopAppBar 小标题自动水平居中，
                // 与 HyperOS 闹钟批量顶栏（居中"已选择N项"）同款
                largeTitle = if (inBatchSelection) "" else currentTitle,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    if (inBatchSelection) {
                        // 批量模式：返回箭头 → ✕ 退出选择（HyperOS 批量惯例）
                        IconButton(onClick = { shortcutSelectionBar.onExit?.invoke() }) {
                            Icon(
                                imageVector = MiuixIcons.Close,
                                contentDescription = stringResource(R.string.layout_sheet_cancel)
                            )
                        }
                    } else {
                        AnimatedVisibility(
                            visible = activeStack.size > 1,
                            enter = slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(300, easing = DecelerateEasing(1.0f))
                            ) + fadeIn(animationSpec = tween(300, easing = DecelerateEasing(1.0f))),
                            exit = slideOutHorizontally(
                                targetOffsetX = { -it },
                                animationSpec = tween(300, easing = AccelerateEasing(1.0f))
                            ) + fadeOut(animationSpec = tween(300, easing = AccelerateEasing(1.0f)))
                        ) {
                            IconButton(onClick = { popLast(activeStack) }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        }
                    }
                },
                actions = {
                    val top = activeStack.last()
                    if (inBatchSelection) {
                        // 右侧全选图标（HyperOS 闹钟批量顶栏同槽位）；标签恒"全选"，
                        // 行为=全选/取消全选切换（MIUI 批量惯例）
                        IconButton(onClick = { shortcutSelectionBar.onToggleAll?.invoke() }) {
                            Icon(
                                imageVector = MiuixIcons.SelectAll,
                                contentDescription = stringResource(R.string.shortcut_select_all)
                            )
                        }
                    } else when {
                        top == SettingsKey.ShortcutList -> {
                            // 首页右上角＋：打开添加 sheet（添加页已取消）。
                            // 该页读本地盘，不加刷新图标——revision 令牌已覆盖「添加后不更新」
                            IconButton(onClick = { shortcutSelectionBar.onAdd?.invoke() }) {
                                Icon(
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = stringResource(R.string.shortcut_add_title)
                                )
                            }
                        }
                        top is SettingsKey.GroupEdit -> {
                            // 组详情：＋添加成员（页内选择器）+ ✓ 保存
                            IconButton(onClick = { groupEditBar.onAddMember?.invoke() }) {
                                Icon(
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = stringResource(R.string.group_member_picker_title)
                                )
                            }
                            IconButton(onClick = { groupEditBar.onSave?.invoke() }) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Check,
                                    contentDescription = stringResource(R.string.group_edit_save),
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                        top is SettingsKey.ShortcutEdit -> {
                            IconButton(onClick = { shortcutEditBar.onSave?.invoke() }) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Check,
                                    contentDescription = stringResource(R.string.shortcut_save),
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                        top == SettingsKey.ShortcutPicker ||
                            top == SettingsKey.QsTilePicker -> {
                            // 数据源在 App 之外（PM / LauncherApps / :ui 准入）→ 主动刷新
                            IconButton(onClick = { refreshBar.onRefresh?.invoke() }) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    contentDescription = stringResource(R.string.logs_refresh),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        CompositionLocalProvider(LocalTopAppBarScrollBehavior provides topAppBarScrollBehavior) {
            NavDisplay(
                backStack = activeStack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                effects = NavDisplayEffects(
                    cornerClipRadius = rememberNavSystemCornerRadius(),
                    backdropColor = MiuixTheme.colorScheme.surface
                )
            ) {
            entry<SettingsKey.TabSettings> {
                RootPageContainer(
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it }
                ) {
                    SettingsPage(
                        prefs = prefs,
                        status = moduleStatus,
                        currentThemeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onNavigateToAppSelection = {
                            settingsStack.add(
                                SettingsKey.AppSelection(PrefKeys.CUSTOM_APPS, selectAppsTitle)
                            )
                        },
                        onNavigateToShortcutSelection = {
                            settingsStack.add(SettingsKey.ShortcutList)
                        },
                        onNavigateToInvokeSettings = {
                            settingsStack.add(SettingsKey.InvokeSettings)
                        },
                        onNavigateToFanBackground = {
                            settingsStack.add(SettingsKey.FanBackground)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            entry<SettingsKey.TabAbout> {
                RootPageContainer(
                    activeTab = activeTab,
                    onSelectTab = { activeTab = it }
                ) {
                    AboutPage(
                        service = service,
                        prefs = prefs,
                        prefsRevision = prefsRevision,
                        onNavigateToDiagnostics = {
                            aboutStack.add(SettingsKey.Diagnostics)
                        },
                        onNavigateToTechInfo = {
                            aboutStack.add(SettingsKey.TechInfo)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            entry<SettingsKey.AppSelection> { key ->
                DetailPageContainer {
                    AppSelectionPage(
                        prefs = prefs,
                        prefsKey = key.prefsKey
                    )
                }
            }
            entry<SettingsKey.ShortcutList> {
                DetailPageContainer {
                    ShortcutListPage(
                        prefs = prefs,
                        bar = shortcutSelectionBar,
                        revision = shortcutRevision,
                        onEdit = { shortcut ->
                            // 组走专属编辑页（C8，§B0：组不经 ShortcutEditPage）
                            if (shortcut.kind == com.lsp.hypersidebar.util.ShortcutKind.GROUP) {
                                settingsStack.add(SettingsKey.GroupEdit(shortcut, isNew = false))
                            } else {
                                settingsStack.add(SettingsKey.ShortcutEdit(shortcut, isNew = false))
                            }
                        },
                        // 多选「组成分组」（布局 §4）：已选 2~3 个普通项预填建组，
                        // 组插入位次由 GroupEditPage 落盘逻辑定（首位成员位置）
                        onCreateGroup = { members ->
                            settingsStack.add(
                                SettingsKey.GroupEdit(
                                    com.lsp.hypersidebar.util.ShortcutAction(
                                        id = UUID.randomUUID().toString(),
                                        kind = com.lsp.hypersidebar.util.ShortcutKind.GROUP,
                                        label = "",
                                        enabled = true,
                                        children = members
                                    ),
                                    isNew = true
                                )
                            )
                        }
                    )
                }
            }
            entry<SettingsKey.GroupEdit> { key ->
                DetailPageContainer {
                    GroupEditPage(
                        prefs = prefs,
                        group = key.group,
                        isNew = key.isNew,
                        revision = shortcutRevision,
                        bar = groupEditBar,
                        onEditMember = { member ->
                            // 成员进现有编辑页（§5.1）；返回经 revision 重读盘上组数据
                            settingsStack.add(SettingsKey.ShortcutEdit(member, isNew = false))
                        },
                        onBack = {
                            // 组页保存/解散/取消都在页内发生——退出即自增刷新令牌（多刷无害）
                            shortcutRevision++
                            popLast(settingsStack)
                        }
                    )
                }
            }
            entry<SettingsKey.ShortcutEdit> { key ->
                DetailPageContainer {
                    ShortcutEditPage(
                        shortcut = key.shortcut,
                        isNew = key.isNew,
                        prefs = prefs,
                        bar = shortcutEditBar,
                        initialTargetSpec = SplitResult(
                            pkg = key.shortcut.packageName ?: "",
                            act = key.shortcut.activityName ?: ""
                        ),
                        onSave = { updated ->
                            if (key.isNew) {
                                ShortcutStore.addShortcut(prefs, updated)
                            } else {
                                ShortcutStore.updateShortcut(prefs, updated)
                            }
                            shortcutRevision++
                            popLast(settingsStack)
                        },
                        onDelete = if (key.isNew) null else {
                            {
                                ShortcutStore.removeShortcut(prefs, key.shortcut.id)
                                shortcutRevision++
                                popLast(settingsStack)
                            }
                        },
                        onPickActivity = { settingsStack.add(SettingsKey.ShortcutPicker) },
                        onPickQsTile = { settingsStack.add(SettingsKey.QsTilePicker) },
                        onBack = { popLast(settingsStack) }
                    )
                }
            }
            entry<SettingsKey.Logs> {
                DetailPageContainer {
                    LogPage(
                        prefs = prefs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            entry<SettingsKey.Stats> {
                DetailPageContainer {
                    StatsPage(modifier = Modifier.fillMaxSize())
                }
            }
            entry<SettingsKey.Diagnostics> {
                DetailPageContainer {
                    DiagnosticsPage(
                        service = service,
                        prefs = prefs,
                        onNavigateToLogs = { aboutStack.add(SettingsKey.Logs) },
                        onNavigateToStats = { aboutStack.add(SettingsKey.Stats) }
                    )
                }
            }
            entry<SettingsKey.TechInfo> {
                DetailPageContainer {
                    TechInfoPage(
                        service = service,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            entry<SettingsKey.InvokeSettings> {
                DetailPageContainer {
                    InvokeSettingsPage(
                        prefs = prefs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            entry<SettingsKey.FanBackground> {
                DetailPageContainer {
                    FanBackgroundPage(
                        prefs = prefs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            entry<SettingsKey.QsTilePicker> {
                DetailPageContainer {
                    QsTilePickerPage(
                        bar = refreshBar,
                        onSelected = { item, isTile ->
                            // 与 ShortcutPicker 同款回填：原地替换栈中编辑键 + 弹出选择器。
                            // 磁贴→QS_TILE（类名存 serviceName）；应用快捷方式→COMPONENT
                            //（目标 activity am start 直启，C2 定案）
                            val idx = settingsStack.indexOfLast { it is SettingsKey.ShortcutEdit }
                            if (idx >= 0) {
                                val cur = settingsStack[idx] as SettingsKey.ShortcutEdit
                                // 快捷方式三分：动态/固定项（shortcutId 非空）→ SHORTCUT_ID
                                //（startShortcut 桌面桥代发，目标 intent 非桌面不可见）；
                                // manifest 项 → COMPONENT（activity am start 直启）
                                val dynamic = !isTile && item.shortcutId != null
                                settingsStack[idx] = cur.copy(
                                    shortcut = cur.shortcut.copy(
                                        packageName = item.packageName,
                                        activityName = if (isTile || dynamic) null else item.className,
                                        serviceName = if (isTile) item.className else null,
                                        shortcutId = if (dynamic) item.shortcutId else null,
                                        kind = when {
                                            isTile -> com.lsp.hypersidebar.util.ShortcutKind.QS_TILE
                                            dynamic -> com.lsp.hypersidebar.util.ShortcutKind.SHORTCUT_ID
                                            else -> com.lsp.hypersidebar.util.ShortcutKind.COMPONENT
                                        },
                                        label = cur.shortcut.label.ifEmpty { item.label },
                                        iconPackageName = item.packageName
                                    )
                                )
                            }
                            popLast(settingsStack)
                        }
                    )
                }
            }
            entry<SettingsKey.ShortcutPicker> {
                DetailPageContainer {
                    ActivityPickerPage(
                        bar = refreshBar,
                        onSelected = { pkg, act, label, isQsTile ->
                            // 选择器回填：原地替换栈中编辑键 + 弹出选择器（同帧），
                            // 编辑页从更新后的 shortcut 重建字段（等价旧 when() 销毁重建语义）
                            val idx = settingsStack.indexOfLast { it is SettingsKey.ShortcutEdit }
                            if (idx >= 0) {
                                val cur = settingsStack[idx] as SettingsKey.ShortcutEdit
                                settingsStack[idx] = cur.copy(
                                    shortcut = cur.shortcut.copy(
                                        packageName = pkg,
                                        // QS 磁贴：类名走 serviceName（与 SERVICE 同构，编辑页
                                        // 保存映射按 kind 分支），kind 显式置 QS_TILE——
                                        // 否则 COMPONENT 探测会把它当普通 Service startService（无效）
                                        activityName = if (isQsTile) null else act,
                                        serviceName = if (isQsTile) act else cur.shortcut.serviceName,
                                        kind = when {
                                            isQsTile -> com.lsp.hypersidebar.util.ShortcutKind.QS_TILE
                                            cur.shortcut.kind == com.lsp.hypersidebar.util.ShortcutKind.QS_TILE ->
                                                com.lsp.hypersidebar.util.ShortcutKind.COMPONENT
                                            else -> cur.shortcut.kind
                                        },
                                        // 仅当当前名称为空时才用 Activity 标签自动填充，避免覆盖用户已输入的名称
                                        label = cur.shortcut.label.ifEmpty { label },
                                        iconPackageName = pkg
                                    )
                                )
                            }
                            popLast(settingsStack)
                        }
                    )
                }
            }
            }
        }
    }
}

/**
 * 根 Tab 页容器：内容 + 常驻底部导航栏（详情页推入时整体作为旧场景滑出，底栏随行——
 * 等价旧 detail overlay 覆盖底栏的观感；底栏在场景内也避免了底栏显隐导致的 content 尺寸变化）。
 * 不透明背景 = U0 教训（过渡期新旧 scene 同组合，透明页面叠透成残影）。
 */
@Composable
private fun RootPageContainer(
    activeTab: Int,
    onSelectTab: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        content()
        RootTabBar(activeTab = activeTab, onSelect = onSelectTab)
    }
}

@Composable
private fun RootTabBar(activeTab: Int, onSelect: (Int) -> Unit) {
    // defaultWindowInsetsPadding=false：Scaffold 已把系统栏高度作为内容 padding 给到本容器，
    // 底栏再吃一次 navigationBars insets ⇒ 底栏比 HyperOS 原生高出一个手势条高度
    NavigationBar(
        mode = NavigationBarDisplayMode.IconAndText,
        defaultWindowInsetsPadding = false
    ) {
        NavigationBarItem(
            selected = activeTab == 0,
            onClick = { onSelect(0) },
            icon = MiuixIcons.Settings,
            label = stringResource(R.string.tab_settings)
        )
        NavigationBarItem(
            selected = activeTab == 1,
            onClick = { onSelect(1) },
            icon = MiuixIcons.Info,
            label = stringResource(R.string.tab_about)
        )
    }
}

@Composable
private fun DetailPageContainer(content: @Composable () -> Unit) {
    // U0 教训：NavDisplay 过渡期新旧 scene 同组合，页面必须带不透明背景。
    // 用 surface（非 background）：卡片是 surfaceContainer，浅色 #FFF 卡压在 #F7F7F7 页上，
    // 深色 #242424 卡压在 #000 页上 —— 这才是 miuix 的层级（background 与卡片同色，边界会消失）
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
    ) {
        content()
    }
}

private fun moduleStatusOf(service: XposedService?): ModuleStatus {
    // 仅在 service 首次绑定（启动时）验证一次，结果固定不再实时刷新。
    if (service == null) return ModuleStatus.INACTIVE
    val scope = runCatching { service.scope }.getOrDefault(emptyList())
    return if (scope.contains(HostPackages.UI_HOST)) {
        ModuleStatus.ACTIVE
    } else {
        ModuleStatus.INACTIVE
    }
}
