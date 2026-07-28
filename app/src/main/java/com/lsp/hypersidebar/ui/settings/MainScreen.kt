package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.ThemeMode
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.anim.AccelerateEasing
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainScreen(
    activity: ComponentActivity,
    prefs: SharedPreferences,
    service: XposedService?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    var selectedTab by remember { mutableStateOf(RootTab.HOME) }
    var detailScreen by remember { mutableStateOf<DetailScreen?>(null) }
    var prefsRevision by remember(prefs) { mutableIntStateOf(0) }
    val moduleStatus by produceState<ModuleStatus>(
        initialValue = ModuleStatus.UNKNOWN,
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) { moduleStatusOf(service) }
    }

    val selectAppsTitle = stringResource(R.string.select_apps)
    val currentTitle = when (val detail = detailScreen) {
        is DetailScreen.AppSelection -> detail.title
        DetailScreen.ShortcutSettings -> stringResource(R.string.shortcuts_add_section)
        DetailScreen.LayoutSettings -> stringResource(R.string.layout_settings)
        DetailScreen.InteractionSettings -> stringResource(R.string.interaction_settings)
        null -> when (selectedTab) {
            RootTab.HOME -> stringResource(R.string.app_name)
            RootTab.SETTINGS -> stringResource(R.string.tab_settings)
            RootTab.ABOUT -> stringResource(R.string.tab_about)
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            prefsRevision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(activity, detailScreen) {
        val callback = object : OnBackPressedCallback(detailScreen != null) {
            override fun handleOnBackPressed() {
                detailScreen = null
            }
        }
        activity.onBackPressedDispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = currentTitle,
                navigationIcon = {
                    AnimatedVisibility(
                        visible = detailScreen != null,
                        enter = slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = tween(300, easing = DecelerateEasing(1.0f))
                        ) + fadeIn(animationSpec = tween(300, easing = DecelerateEasing(1.0f))),
                        exit = slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(300, easing = AccelerateEasing(1.0f))
                        ) + fadeOut(animationSpec = tween(300, easing = AccelerateEasing(1.0f)))
                    ) {
                        IconButton(onClick = { detailScreen = null }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                }
            )
        }
        // bottomBar 移到 content 内的 Column —— 避免子页面进出时 content 区域尺寸变化打断 slide 动画
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 根内容：根Tab + 底部导航栏（一起作为常驻底层，被 overlay 覆盖时自然隐藏）
            Column(modifier = Modifier.fillMaxSize()) {
                // 根 Tab 常驻 —— 进出子页面都不销毁，FanPreviewCard 的 remember 缓存保住
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300, easing = DecelerateEasing(1.0f))) togetherWith
                            fadeOut(animationSpec = tween(300, easing = AccelerateEasing(1.0f)))
                    },
                    label = "root-tabs"
                ) { tab ->
                    when (tab) {
                        RootTab.HOME -> HomePage(
                            prefs = prefs,
                            prefsRevision = prefsRevision,
                            status = moduleStatus,
                            service = service
                        )
                        RootTab.SETTINGS -> SettingsPage(
                            prefs = prefs,
                            prefsRevision = prefsRevision,
                            currentThemeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            onNavigateToAppSelection = {
                                detailScreen = DetailScreen.AppSelection(PrefKeys.CUSTOM_APPS, selectAppsTitle)
                            },
                            onNavigateToShortcutSelection = {
                                detailScreen = DetailScreen.ShortcutSettings
                            },
                            onNavigateToLayout = { detailScreen = DetailScreen.LayoutSettings },
                            onNavigateToInteraction = { detailScreen = DetailScreen.InteractionSettings }
                        )
                        RootTab.ABOUT -> AboutPage()
                    }
                }

                // 底部导航栏：常驻渲染。子页面 overlay 滑入时覆盖它、滑出时露出它，避免进入子页面时底栏突然消失、根内容突然拉伸
                NavigationBar(
                    mode = NavigationBarDisplayMode.IconAndText
                ) {
                    NavigationBarItem(
                        selected = selectedTab == RootTab.HOME,
                        onClick = { selectedTab = RootTab.HOME },
                        icon = MiuixIcons.All,
                        label = stringResource(R.string.tab_home)
                    )
                    NavigationBarItem(
                        selected = selectedTab == RootTab.SETTINGS,
                        onClick = { selectedTab = RootTab.SETTINGS },
                        icon = MiuixIcons.Settings,
                        label = stringResource(R.string.tab_settings)
                    )
                    NavigationBarItem(
                        selected = selectedTab == RootTab.ABOUT,
                        onClick = { selectedTab = RootTab.ABOUT },
                        icon = MiuixIcons.Info,
                        label = stringResource(R.string.tab_about)
                    )
                }
            }

            // 子页面浮层 —— detail != null 时盖在根内容上（含 bottomBar 区域）
            AnimatedContent(
                targetState = detailScreen,
                transitionSpec = {
                    val durationMillis = 300
                    if (targetState != null) {
                        // 进入：DecelerateEasing（快开始慢结束）—— HyperOS 设置页进入风格
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(durationMillis, easing = DecelerateEasing(1.0f))
                        ) togetherWith slideOutHorizontally(
                            targetOffsetX = { -it / 6 },
                            animationSpec = tween(durationMillis, easing = DecelerateEasing(1.0f))
                        )
                    } else {
                        // 退出：AccelerateEasing（慢开始快结束）—— HyperOS 设置页退出风格
                        slideInHorizontally(
                            initialOffsetX = { -it / 6 },
                            animationSpec = tween(durationMillis, easing = AccelerateEasing(1.0f))
                        ) togetherWith slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(durationMillis, easing = AccelerateEasing(1.0f))
                        )
                    }
                },
                label = "detail-navigation"
            ) { detail ->
                if (detail == null) {
                    Spacer(Modifier.fillMaxSize())
                } else {
                    val overlayInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .clickable(
                                interactionSource = overlayInteractionSource,
                                indication = null,
                            ) {}
                    ) {
                        when (detail) {
                            is DetailScreen.AppSelection -> AppSelectionPage(
                                prefs = prefs,
                                prefsKey = detail.prefsKey
                            )
                            DetailScreen.ShortcutSettings -> ShortcutSettingsPage(
                                prefs = prefs
                            )
                            DetailScreen.LayoutSettings -> LayoutSettingsPage(
                                prefs = prefs,
                                prefsRevision = prefsRevision
                            )
                            DetailScreen.InteractionSettings -> InteractionSettingsPage(
                                prefs = prefs,
                                prefsRevision = prefsRevision
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun moduleStatusOf(service: XposedService?): ModuleStatus {
    if (service == null) return ModuleStatus.UNKNOWN
    val scope = runCatching { service.scope }.getOrDefault(emptyList())
    return if (scope.contains("com.miui.securitycenter")) {
        ModuleStatus.ACTIVE
    } else {
        ModuleStatus.INACTIVE
    }
}
