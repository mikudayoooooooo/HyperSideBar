package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class ModuleStatus { ACTIVE, INACTIVE }

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
