package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.LocalSemanticColors
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import top.yukonga.miuix.kmp.basic.BasicComponent
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

@Composable
internal fun ModuleStatusComponent(status: ModuleStatus) {
    val semantic = LocalSemanticColors.current
    val (accent, title, summary) = when (status) {
        ModuleStatus.ACTIVE -> Triple(
            semantic.success,
            stringResource(R.string.module_active),
            stringResource(R.string.module_active_summary)
        )
        ModuleStatus.INACTIVE -> Triple(
            MiuixTheme.colorScheme.error,
            stringResource(R.string.module_inactive),
            stringResource(R.string.deactivation_hint)
        )
    }

    BasicComponent(
        title = title,
        summary = summary,
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    )
}
