package com.lsp.hypersidebar.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.LayoutDefaults
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.LocalSemanticColors
import com.lsp.hypersidebar.ui.fan.FanThemeColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
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
internal fun ModuleStatusComponent(
    status: ModuleStatus,
    probe: ModuleProbeState?,
    onRetry: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val errorColor = MiuixTheme.colorScheme.error

    // 异常端（code 非 1）：黄卡只列异常端，正常态不显示探针细节
    val launcherIssue = probe?.launcher?.takeIf { it != PrefKeys.PROBE_CODE_OK }
    val executorIssue = probe?.executor?.takeIf { it != PrefKeys.PROBE_CODE_OK }
    // 熔断才可点（手动重试）；降级/无应答无即时动作，未激活纯展示（不跳 LSPosed）
    val anyCircuit = launcherIssue == PrefKeys.PROBE_CODE_CIRCUIT ||
        executorIssue == PrefKeys.PROBE_CODE_CIRCUIT

    val bg: Color
    val dot: Color
    val title: String
    val summary: String
    when {
        status == ModuleStatus.INACTIVE -> {
            bg = errorColor.copy(alpha = 0.12f)
            dot = errorColor
            title = stringResource(R.string.module_inactive)
            summary = stringResource(R.string.deactivation_hint)
        }
        launcherIssue != null || executorIssue != null -> {
            bg = semantic.warningContainer
            dot = semantic.warning
            title = stringResource(R.string.status_abnormal)
            summary = listOfNotNull(
                launcherIssue?.let {
                    stringResource(R.string.probe_end_home) + "：" + probeStateText(it)
                },
                executorIssue?.let {
                    stringResource(R.string.probe_end_ui) + "：" + probeStateText(it)
                }
            ).joinToString("\n")
        }
        else -> {
            bg = semantic.successContainer
            dot = semantic.success
            title = stringResource(R.string.status_running)
            summary = stringResource(R.string.status_normal_summary)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (anyCircuit) Modifier.clickable { onRetry() } else Modifier),
        colors = CardDefaults.defaultColors(color = bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun probeStateText(code: Int): String = when (code) {
    PrefKeys.PROBE_CODE_CIRCUIT -> stringResource(R.string.probe_state_circuit)
    PrefKeys.PROBE_CODE_DEGRADED -> stringResource(R.string.probe_state_degraded)
    else -> stringResource(R.string.probe_state_dead)
}
