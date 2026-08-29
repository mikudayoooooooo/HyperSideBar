package com.lsp.hypersidebar.ui.settings

import com.lsp.hypersidebar.prefs.savePref
import com.lsp.hypersidebar.ui.fan.effectiveIconSizeDp
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
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
    prefsRevision: Int,
    status: ModuleStatus,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToAppSelection: () -> Unit,
    onNavigateToShortcutSelection: () -> Unit,
    onNavigateToLayout: () -> Unit,
    onNavigateToInteraction: () -> Unit,
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
                    ArrowPreference(
                        title = stringResource(R.string.layout_settings),
                        summary = stringResource(R.string.layout_settings_summary),
                        onClick = onNavigateToLayout
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.interaction)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.interaction_settings),
                        summary = stringResource(R.string.interaction_settings_summary),
                        onClick = onNavigateToInteraction
                    )
                }
            }
        }
    }
}

@Composable
internal fun LayoutSettingsPage(
    prefs: SharedPreferences,
    prefsRevision: Int,
    modifier: Modifier = Modifier
) {
    var iconSize by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE))
    }
    var innerRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS))
    }
    var outerRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX))
    }
    var outerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER).toFloat())
    }
    var innerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER).toFloat())
    }
    var landscapeIconSize by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE))
    }
    var landscapeOuterCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER).toFloat())
    }
    var landscapeInnerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER).toFloat())
    }
    var landscapeInnerRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS))
    }
    var landscapeOuterRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS))
    }

    // 行为规则 1：设置页显示弦长收缩后的实际生效图标尺寸，超限时提示
    val density = LocalDensity.current.density
    val effectiveIcon = effectiveIconSizeDp(
        outerCount.toInt(), innerCount.toInt(), 150f,
        outerRadius, innerRadius, iconSize, density
    )
    val effectiveLandscapeIcon = effectiveIconSizeDp(
        landscapeOuterCount.toInt(), landscapeInnerCount.toInt(), 75f,
        landscapeOuterRadius, landscapeInnerRadius, landscapeIconSize, density
    )

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.portrait_layout)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderItem(
                        title = stringResource(R.string.icon_size),
                        summary = if (effectiveIcon < iconSize) {
                            stringResource(R.string.icon_size_limited, iconSize.toInt(), effectiveIcon.toInt())
                        } else {
                            stringResource(R.string.icon_size_effective, iconSize.toInt(), effectiveIcon.toInt())
                        },
                        value = iconSize,
                        valueRange = 32f..80f,
                        onValueChange = { iconSize = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.ICON_SIZE, iconSize) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.inner_radius),
                        summary = stringResource(R.string.inner_radius_summary, innerRadius.toInt()),
                        value = innerRadius,
                        valueRange = 80f..160f,
                        steps = 7,
                        onValueChange = { innerRadius = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.INNER_RADIUS, innerRadius) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.outer_radius_max),
                        summary = stringResource(R.string.outer_radius_summary, outerRadius.toInt()),
                        value = outerRadius,
                        valueRange = 110f..220f,
                        steps = 10,
                        onValueChange = { outerRadius = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.OUTER_RADIUS_MAX, outerRadius) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.outer_apps_count),
                        summary = stringResource(R.string.outer_apps_summary, outerCount.toInt()),
                        value = outerCount,
                        valueRange = 4f..12f,
                        steps = 7,
                        onValueChange = { outerCount = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.MAX_APPS_OUTER, outerCount.toInt())
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.inner_apps_count),
                        summary = stringResource(R.string.inner_apps_summary, innerCount.toInt()),
                        value = innerCount,
                        valueRange = 2f..8f,
                        steps = 5,
                        onValueChange = { innerCount = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.MAX_APPS_INNER, innerCount.toInt())
                        }
                    )
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.landscape_layout)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_icon_size),
                        summary = if (effectiveLandscapeIcon < landscapeIconSize) {
                            stringResource(R.string.landscape_icon_size_limited, landscapeIconSize.toInt(), effectiveLandscapeIcon.toInt())
                        } else {
                            stringResource(R.string.landscape_icon_size_effective, landscapeIconSize.toInt(), effectiveLandscapeIcon.toInt())
                        },
                        value = landscapeIconSize,
                        valueRange = 32f..80f,
                        onValueChange = { landscapeIconSize = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_ICON_SIZE, landscapeIconSize)
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_outer_apps_count),
                        summary = stringResource(R.string.landscape_outer_apps_summary, landscapeOuterCount.toInt()),
                        value = landscapeOuterCount,
                        valueRange = 3f..8f,
                        steps = 4,
                        onValueChange = { landscapeOuterCount = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, landscapeOuterCount.toInt())
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_inner_apps_count),
                        summary = stringResource(R.string.landscape_inner_apps_summary, landscapeInnerCount.toInt()),
                        value = landscapeInnerCount,
                        valueRange = 0f..6f,
                        steps = 5,
                        onValueChange = { landscapeInnerCount = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_MAX_APPS_INNER, landscapeInnerCount.toInt())
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_inner_radius),
                        summary = stringResource(R.string.landscape_inner_radius_summary, landscapeInnerRadius.toInt()),
                        value = landscapeInnerRadius,
                        valueRange = 80f..160f,
                        steps = 7,
                        onValueChange = { landscapeInnerRadius = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_INNER_RADIUS, landscapeInnerRadius)
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_outer_radius),
                        summary = stringResource(R.string.landscape_outer_radius_summary, landscapeOuterRadius.toInt()),
                        value = landscapeOuterRadius,
                        valueRange = 110f..220f,
                        steps = 10,
                        onValueChange = { landscapeOuterRadius = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_OUTER_RADIUS, landscapeOuterRadius)
                        }
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.restore_defaults),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { restoreLayoutDefaults(prefs) })
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
internal fun InteractionSettingsPage(
    prefs: SharedPreferences,
    prefsRevision: Int,
    modifier: Modifier = Modifier
) {
    var activeZone by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.ACTIVE_ZONE, LayoutDefaults.ACTIVE_ZONE)) }
    var deadZone by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.DEAD_ZONE, LayoutDefaults.DEAD_ZONE)) }
    var vibrate by remember(prefs, prefsRevision) { mutableStateOf(prefs.getBoolean(PrefKeys.VIBRATE, LayoutDefaults.VIBRATE)) }

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.interaction_settings)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderItem(
                        title = stringResource(R.string.stick_sensitivity),
                        summary = stringResource(R.string.stick_sensitivity_description, activeZone.toInt()),
                        value = activeZone,
                        valueRange = 120f..300f,
                        steps = 8,
                        onValueChange = { activeZone = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.ACTIVE_ZONE, activeZone) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.dead_zone),
                        summary = stringResource(R.string.dead_zone_description, deadZone.toInt()),
                        value = deadZone,
                        valueRange = 4f..40f,
                        steps = 8,
                        onValueChange = { deadZone = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.DEAD_ZONE, deadZone) }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.vibrate_feedback),
                        summary = stringResource(R.string.vibrate_feedback_summary),
                        checked = vibrate,
                        onCheckedChange = {
                            vibrate = it
                            prefs.savePref(PrefKeys.VIBRATE, it)
                        }
                    )
                }
            }
        }
    }
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
private fun SettingsSliderItem(
    title: String,
    summary: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0
) {
    BasicComponent(
        title = title,
        summary = summary,
        bottomAction = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
    )
}

private fun restoreLayoutDefaults(prefs: SharedPreferences) {
    // 单 editor 批量落盘：1 次 binder 写 + 1 次监听器回调 + 1 轮重组（原逐 key 写 = 10 轮）
    prefs.edit().apply {
        putFloat(PrefKeys.ICON_SIZE, LayoutDefaults.ICON_SIZE)
        putFloat(PrefKeys.INNER_RADIUS, LayoutDefaults.INNER_RADIUS)
        putFloat(PrefKeys.OUTER_RADIUS_MAX, LayoutDefaults.OUTER_RADIUS_MAX)
        putInt(PrefKeys.MAX_APPS_OUTER, LayoutDefaults.MAX_APPS_OUTER)
        putInt(PrefKeys.MAX_APPS_INNER, LayoutDefaults.MAX_APPS_INNER)
        putFloat(PrefKeys.LANDSCAPE_ICON_SIZE, LayoutDefaults.LANDSCAPE_ICON_SIZE)
        putInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, LayoutDefaults.LANDSCAPE_MAX_APPS_OUTER)
        putInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, LayoutDefaults.LANDSCAPE_MAX_APPS_INNER)
        putFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, LayoutDefaults.LANDSCAPE_INNER_RADIUS)
        putFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, LayoutDefaults.LANDSCAPE_OUTER_RADIUS)
    }.apply()
}
