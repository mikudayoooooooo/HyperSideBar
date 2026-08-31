package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.prefs.SettingsRepository
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun SettingsPage(
    prefs: SharedPreferences,
    repo: SettingsRepository,
    prefsRevision: Int,
    status: ModuleStatus,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToAppSelection: () -> Unit,
    onNavigateToShortcutSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember(prefs, prefsRevision) {
        mutableStateOf(prefs.getBoolean(PrefKeys.ENABLED, true))
    }
    val selectedApps = remember(prefs, prefsRevision) {
        prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty().size
    }
    val shortcutCount = remember(prefs, prefsRevision) {
        ShortcutStore.loadUserShortcuts(prefs).size
    }
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )
    val baseMode = ThemeModes.baseMode(currentThemeMode)
    val selectedThemeIndex = ThemeModes.BASE_MODES.indexOf(baseMode).coerceAtLeast(0)
    val useSystemColors = ThemeModes.usesSystemColors(currentThemeMode)

    // 降级/熔断状态（1C：hook 侧写入 remotePrefs；迁自退役的 HomePage）。
    // 熔断按进程分键（home/ui），任一端熔断即显示；显示优先级：熔断 > 降级
    val passthroughDegraded = remember(prefs, prefsRevision) {
        runCatching { prefs.getBoolean(PrefKeys.PASSTHROUGH_DEGRADED, false) }.getOrDefault(false)
    }
    val circuitOpen = remember(prefs, prefsRevision) {
        runCatching {
            prefs.getBoolean(PrefKeys.CIRCUIT_OPEN_HOME, false) ||
                prefs.getBoolean(PrefKeys.CIRCUIT_OPEN_UI, false)
        }.getOrDefault(false)
    }

    // 布局编辑 BottomSheet：入口 = 布局预览卡双缩略点击（§2.2）
    var sheetOrientation by remember { mutableStateOf<LayoutOrientation?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }

    fun openLayoutSheet(orientation: LayoutOrientation) {
        repo.discardDraft() // 兜底清残留（上次关闭未走 onDismissFinished 的极端路径）
        sheetOrientation = orientation
        sheetVisible = true
    }

    // 草稿守卫：sheet 关闭或页面离开组合（含切 Tab 丢 sheet 状态）时兜底丢弃，
    // 防止残留草稿持续泄漏进预览卡的草稿优先读（"没保存却生效"的观感来源）
    DisposableEffect(sheetOrientation) {
        onDispose { repo.discardDraft() }
    }

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.module_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ModuleStatusComponent(status = status)
                    SwitchPreference(
                        title = stringResource(R.string.module_enabled),
                        summary = stringResource(R.string.module_enabled_summary),
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.savePref(PrefKeys.ENABLED, it)
                        }
                    )
                }
            }
        }

        if (circuitOpen) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    CircuitStatusComponent(
                        onRetry = {
                            // 手动重试：写时间戳，hook 侧比较 resetAt > 本端熔断时刻即解除
                            //（launcher=下次边缘呼出，:ui=2s 看门狗内）
                            prefs.edit()
                                .putLong(PrefKeys.CIRCUIT_RESET_AT, System.currentTimeMillis())
                                .commit()
                        }
                    )
                }
            }
        } else if (passthroughDegraded) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    DegradedStatusComponent()
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.effect_preview)) }
        item {
            LayoutPreviewCard(
                repo = repo,
                onPortraitClick = { openLayoutSheet(LayoutOrientation.PORTRAIT) },
                onLandscapeClick = { openLayoutSheet(LayoutOrientation.LANDSCAPE) }
            )
        }

        item { SmallTitle(text = stringResource(R.string.apps_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.select_apps),
                        summary = stringResource(R.string.selected_apps_summary, selectedApps),
                        onClick = onNavigateToAppSelection
                    )
                    ArrowPreference(
                        title = stringResource(R.string.select_shortcut_apps),
                        summary = stringResource(R.string.selected_apps_summary, shortcutCount),
                        onClick = onNavigateToShortcutSelection
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.appearance)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OverlayDropdownMenu(
                        title = stringResource(R.string.theme_mode),
                        options = themeOptions,
                        selectedIndex = selectedThemeIndex,
                        onSelectedIndexChange = { index ->
                            val selectedBase = ThemeModes.BASE_MODES[index]
                            onThemeModeChange(ThemeModes.compose(selectedBase, useSystemColors))
                        }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.system_colors),
                        summary = stringResource(R.string.system_colors_summary),
                        checked = useSystemColors,
                        onCheckedChange = { enabled ->
                            onThemeModeChange(ThemeModes.compose(baseMode, enabled))
                        }
                    )
                }
            }
        }
    }

    // 布局编辑 sheet 常驻组合（show 控制显隐）；
    // 关闭走两段：onDismiss 收 show（内容随 sheet 滑下）→ onDismissFinished 清理草稿与方向
    LayoutBottomSheet(
        show = sheetVisible,
        orientation = sheetOrientation ?: LayoutOrientation.PORTRAIT,
        repo = repo,
        onDismiss = { sheetVisible = false },
        onDismissFinished = {
            // 保存路径 commit 已清空草稿，此处为无操作；取消/滑掉/返回=丢弃
            repo.discardDraft()
            sheetOrientation = null
        }
    )
}

@Composable
private fun SettingsList(
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun DegradedStatusComponent() {
    BasicComponent(
        title = stringResource(R.string.passthrough_degraded),
        summary = stringResource(R.string.passthrough_degraded_summary),
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.error)
            )
        }
    )
}

@Composable
private fun CircuitStatusComponent(onRetry: () -> Unit) {
    BasicComponent(
        title = stringResource(R.string.circuit_open),
        summary = stringResource(R.string.circuit_open_summary),
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.error)
            )
        },
        onClick = onRetry
    )
}

@Composable
internal fun SettingsSliderItem(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0,
    sliderHorizontalPadding: Dp = 16.dp,
    compact: Boolean = false
) {
    BasicComponent(
        title = title,
        summary = summary,
        insideMargin = if (compact) SheetSliderInsideMargin else BasicComponentDefaults.InsideMargin,
        bottomAction = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sliderHorizontalPadding)
            )
        }
    )
}
