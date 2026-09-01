package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec
import com.lsp.hypersidebar.R
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

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
    val settingsStack = remember { NavBackStack<SettingsKey>(SettingsKey.TabSettings) }
    val aboutStack = remember { NavBackStack<SettingsKey>(SettingsKey.TabAbout) }
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    val activeStack = if (activeTab == 0) settingsStack else aboutStack

    val selectAppsTitle = stringResource(R.string.select_apps)
    val currentTitle = when (val top = activeStack.last()) {
        SettingsKey.TabSettings -> stringResource(R.string.tab_settings)
        SettingsKey.TabAbout -> stringResource(R.string.tab_about)
        is SettingsKey.AppSelection -> top.title
        SettingsKey.ShortcutList, SettingsKey.ShortcutPicker ->
            stringResource(R.string.shortcuts_add_section)
        is SettingsKey.ShortcutEdit -> stringResource(R.string.shortcuts_add_section)
    }

    // 批量选择顶栏桥（ShortcutListPage 写 / 本处顶栏读）：HyperOS 批量模式=顶栏变形
    val shortcutSelectionBar = remember { ShortcutSelectionBar() }
    val inBatchSelection = activeStack.lastOrNull() == SettingsKey.ShortcutList &&
        shortcutSelectionBar.active

    // 根 Tab 互切保持现网根 Tab 的淡入淡出；其余（详情推/弹）走 miuix HyperOS 横滑默认
    val rootContentKeys = remember {
        setOf(SettingsKey.TabSettings.toString(), SettingsKey.TabAbout.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (inBatchSelection) {
                    stringResource(R.string.shortcut_selected_count, shortcutSelectionBar.count)
                } else {
                    currentTitle
                },
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
                            IconButton(onClick = { activeStack.removeLast() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (inBatchSelection) {
                        // 标签恒"全选"，行为=全选/取消全选切换（MIUI 批量惯例）
                        TextButton(
                            text = stringResource(R.string.shortcut_select_all),
                            onClick = { shortcutSelectionBar.onToggleAll?.invoke() }
                        )
                        TextButton(
                            text = stringResource(R.string.shortcut_delete),
                            onClick = { shortcutSelectionBar.onRequestDelete?.invoke() },
                            enabled = shortcutSelectionBar.count > 0,
                            colors = ButtonDefaults.textButtonColors(
                                color = MiuixTheme.colorScheme.error
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        NavDisplay(
            backStack = activeStack,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                val fromRoot = rootContentKeys.contains(initialState.entries.lastOrNull()?.contentKey)
                val toRoot = rootContentKeys.contains(targetState.entries.lastOrNull()?.contentKey)
                if (fromRoot && toRoot) {
                    fadeIn(animationSpec = tween(300, easing = DecelerateEasing(1.0f))) togetherWith
                        fadeOut(animationSpec = tween(300, easing = AccelerateEasing(1.0f)))
                } else {
                    defaultTransitionSpec<SettingsKey>().invoke(this)
                }
            },
            popTransitionSpec = { defaultPopTransitionSpec<SettingsKey>().invoke(this) },
            entryProvider = { key ->
                when (key) {
                    SettingsKey.TabSettings -> NavEntry(key) {
                        RootPageContainer(
                            activeTab = activeTab,
                            onSelectTab = { activeTab = it }
                        ) {
                            SettingsPage(
                                prefs = prefs,
                                repo = settingsRepo,
                                prefsRevision = prefsRevision,
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
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SettingsKey.TabAbout -> NavEntry(key) {
                        RootPageContainer(
                            activeTab = activeTab,
                            onSelectTab = { activeTab = it }
                        ) {
                            AboutPage(
                                service = service,
                                prefs = prefs,
                                prefsRevision = prefsRevision,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is SettingsKey.AppSelection -> NavEntry(key) {
                        DetailPageContainer {
                            AppSelectionPage(
                                prefs = prefs,
                                prefsKey = key.prefsKey
                            )
                        }
                    }
                    SettingsKey.ShortcutList -> NavEntry(key) {
                        DetailPageContainer {
                            ShortcutListPage(
                                prefs = prefs,
                                bar = shortcutSelectionBar,
                                onEdit = { shortcut ->
                                    settingsStack.add(SettingsKey.ShortcutEdit(shortcut, isNew = false))
                                },
                                onAdd = { kind ->
                                    settingsStack.add(
                                        SettingsKey.ShortcutEdit(
                                            ShortcutAction(
                                                id = UUID.randomUUID().toString(),
                                                kind = kind,
                                                label = "",
                                                enabled = true
                                            ),
                                            isNew = true
                                        )
                                    )
                                }
                            )
                        }
                    }
                    is SettingsKey.ShortcutEdit -> NavEntry(key) {
                        DetailPageContainer {
                            ShortcutEditPage(
                                shortcut = key.shortcut,
                                isNew = key.isNew,
                                prefs = prefs,
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
                                    settingsStack.removeLast()
                                },
                                onDelete = if (key.isNew) null else {
                                    {
                                        ShortcutStore.removeShortcut(prefs, key.shortcut.id)
                                        settingsStack.removeLast()
                                    }
                                },
                                onPickActivity = { settingsStack.add(SettingsKey.ShortcutPicker) },
                                onBack = { settingsStack.removeLast() }
                            )
                        }
                    }
                    SettingsKey.ShortcutPicker -> NavEntry(key) {
                        DetailPageContainer {
                            ActivityPickerPage(
                                onSelected = { pkg, act, label ->
                                    // 选择器回填：原地替换栈中编辑键 + 弹出选择器（同帧），
                                    // 编辑页从更新后的 shortcut 重建字段（等价旧 when() 销毁重建语义）
                                    val idx = settingsStack.indexOfLast { it is SettingsKey.ShortcutEdit }
                                    if (idx >= 0) {
                                        val cur = settingsStack[idx] as SettingsKey.ShortcutEdit
                                        settingsStack[idx] = cur.copy(
                                            shortcut = cur.shortcut.copy(
                                                packageName = pkg,
                                                activityName = act,
                                                // 仅当当前名称为空时才用 Activity 标签自动填充，避免覆盖用户已输入的名称
                                                label = cur.shortcut.label.ifEmpty { label },
                                                iconPackageName = pkg
                                            )
                                        )
                                    }
                                    settingsStack.removeLast()
                                },
                                onBack = { settingsStack.removeLast() }
                            )
                        }
                    }
                }
            }
        )
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
            .background(MiuixTheme.colorScheme.background)
    ) {
        content()
        RootTabBar(activeTab = activeTab, onSelect = onSelectTab)
    }
}

@Composable
private fun RootTabBar(activeTab: Int, onSelect: (Int) -> Unit) {
    NavigationBar(mode = NavigationBarDisplayMode.IconAndText) {
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
    // U0 教训：NavDisplay 过渡期新旧 scene 同组合，页面必须带不透明背景
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        content()
    }
}

private fun moduleStatusOf(service: XposedService?): ModuleStatus {
    // 仅在 service 首次绑定（启动时）验证一次，结果固定不再实时刷新。
    if (service == null) return ModuleStatus.INACTIVE
    val scope = runCatching { service.scope }.getOrDefault(emptyList())
    return if (scope.contains("com.miui.securitycenter")) {
        ModuleStatus.ACTIVE
    } else {
        ModuleStatus.INACTIVE
    }
}
