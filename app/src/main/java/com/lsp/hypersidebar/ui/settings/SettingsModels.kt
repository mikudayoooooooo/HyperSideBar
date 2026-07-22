package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class ModuleStatus { ACTIVE, INACTIVE, UNKNOWN }

internal object PrefKeys {
    const val ENABLED = "enabled"
    const val THEME_MODE = "themeMode"
    const val ICON_SIZE = "iconSize"
    const val INNER_RADIUS = "innerRadius"
    const val OUTER_RADIUS_MAX = "outerRadiusMax"
    const val MAX_APPS_OUTER = "maxAppsOuter"
    const val MAX_APPS_INNER = "maxAppsInner"
    const val ACTIVE_ZONE = "activeZone"
    const val DEAD_ZONE = "deadZone"
    const val VIBRATE = "vibrateEnabled"
    const val LANDSCAPE_ICON_SIZE = "landscapeIconSize"
    const val LANDSCAPE_MAX_APPS_OUTER = "landscapeMaxAppsOuter"
    const val LANDSCAPE_MAX_APPS_INNER = "landscapeMaxAppsInner"
    const val LANDSCAPE_INNER_RADIUS = "landscapeInnerRadius"
    const val LANDSCAPE_OUTER_RADIUS = "landscapeOuterRadius"
    const val CUSTOM_APPS = "customApps"
    const val SHORTCUT_APPS = "shortcutApps"
}

internal fun SharedPreferences.savePref(key: String, value: Any) {
    edit().apply {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Float -> putFloat(key, value)
            is Int -> putInt(key, value)
            is String -> putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, value as Set<String>)
            }
        }
    }.apply()
}

internal fun openLsposedManager(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName("org.lsposed.manager", "org.lsposed.manager.ui.activity.MainActivity")
        })
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.lsposed_not_found), Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun currentFanThemeColors(): FanThemeColors {
    val scheme = MiuixTheme.colorScheme
    return remember(scheme) {
        FanThemeColors(
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            surface = scheme.surface,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariantSummary,
            outline = scheme.outline,
            background = scheme.background,
            isDark = scheme.background.luminance() < 0.5f
        )
    }
}
