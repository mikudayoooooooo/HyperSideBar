package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.ThemeMode
import com.lsp.hypersidebar.theme.ThemeModes
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsPage(
    prefs: SharedPreferences,
    prefsRevision: Int,
    currentThemeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToAppSelection: () -> Unit,
    onNavigateToShortcutSelection: () -> Unit,
    onNavigateToLayout: () -> Unit,
    onNavigateToInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedApps = remember(prefs, prefsRevision) {
        prefs.getStringSet(PrefKeys.CUSTOM_APPS, emptySet()).orEmpty().size
    }
    val shortcutApps = remember(prefs, prefsRevision) {
        prefs.getStringSet(PrefKeys.SHORTCUT_APPS, emptySet()).orEmpty().size
    }
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )
    val baseMode = ThemeModes.baseMode(currentThemeMode)
    val selectedThemeIndex = ThemeModes.BASE_MODES.indexOf(baseMode).coerceAtLeast(0)
    val useSystemColors = ThemeModes.usesSystemColors(currentThemeMode)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                        summary = stringResource(R.string.selected_apps_summary, shortcutApps),
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
    var iconSize by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.ICON_SIZE, 48f)) }
    var innerRadius by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.INNER_RADIUS, 150f)) }
    var outerRadius by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.OUTER_RADIUS_MAX, 200f)) }
    var outerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.MAX_APPS_OUTER, 7).toFloat())
    }
    var innerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.MAX_APPS_INNER, 4).toFloat())
    }
    var landscapeIconSize by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_ICON_SIZE, 48f))
    }
    var landscapeOuterCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_OUTER, 5).toFloat())
    }
    var landscapeInnerCount by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getInt(PrefKeys.LANDSCAPE_MAX_APPS_INNER, 3).toFloat())
    }
    var landscapeInnerRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_INNER_RADIUS, 150f))
    }
    var landscapeOuterRadius by remember(prefs, prefsRevision) {
        mutableFloatStateOf(prefs.getFloat(PrefKeys.LANDSCAPE_OUTER_RADIUS, 200f))
    }

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.portrait_layout)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderItem(
                        title = stringResource(R.string.icon_size),
                        summary = stringResource(R.string.icon_size_summary, iconSize.toInt()),
                        value = iconSize,
                        valueRange = 32f..80f,
                        onValueChange = { iconSize = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.ICON_SIZE, iconSize) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.inner_radius),
                        summary = stringResource(R.string.inner_radius_summary, innerRadius.toInt()),
                        value = innerRadius,
                        valueRange = 100f..200f,
                        steps = 9,
                        onValueChange = { innerRadius = it },
                        onValueChangeFinished = { prefs.savePref(PrefKeys.INNER_RADIUS, innerRadius) }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.outer_radius_max),
                        summary = stringResource(R.string.outer_radius_summary, outerRadius.toInt()),
                        value = outerRadius,
                        valueRange = 150f..300f,
                        steps = 14,
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
                        summary = stringResource(R.string.landscape_icon_size_summary, landscapeIconSize.toInt()),
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
                        valueRange = 80f..200f,
                        steps = 11,
                        onValueChange = { landscapeInnerRadius = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_INNER_RADIUS, landscapeInnerRadius)
                        }
                    )
                    SettingsSliderItem(
                        title = stringResource(R.string.landscape_outer_radius),
                        summary = stringResource(R.string.landscape_outer_radius_summary, landscapeOuterRadius.toInt()),
                        value = landscapeOuterRadius,
                        valueRange = 120f..300f,
                        steps = 17,
                        onValueChange = { landscapeOuterRadius = it },
                        onValueChangeFinished = {
                            prefs.savePref(PrefKeys.LANDSCAPE_OUTER_RADIUS, landscapeOuterRadius)
                        }
                    )
                }
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
    var activeZone by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.ACTIVE_ZONE, 60f)) }
    var deadZone by remember(prefs, prefsRevision) { mutableFloatStateOf(prefs.getFloat(PrefKeys.DEAD_ZONE, 12f)) }
    var vibrate by remember(prefs, prefsRevision) { mutableStateOf(prefs.getBoolean(PrefKeys.VIBRATE, true)) }

    SettingsList(modifier = modifier) {
        item { SmallTitle(text = stringResource(R.string.interaction_settings)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSliderItem(
                        title = stringResource(R.string.stick_sensitivity),
                        summary = stringResource(R.string.stick_sensitivity_description, activeZone.toInt()),
                        value = activeZone,
                        valueRange = 30f..120f,
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
        modifier = modifier.fillMaxSize(),
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
