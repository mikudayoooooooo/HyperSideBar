package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.prefs.PrefKeys
import com.lsp.hypersidebar.theme.LocalSemanticColors
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun HomePage(
    prefs: SharedPreferences,
    prefsRevision: Int,
    status: ModuleStatus,
    modifier: Modifier = Modifier
) {
    // 降级状态（1C：:ui 穿透失效自动降级时写入 remotePrefs）；revision 变化驱动实时刷新
    val passthroughDegraded = remember(prefs, prefsRevision) {
        runCatching { prefs.getBoolean(PrefKeys.PASSTHROUGH_DEGRADED, false) }.getOrDefault(false)
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (status == ModuleStatus.INACTIVE) {
            item { SmallTitle(text = stringResource(R.string.module_section)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ModuleStatusComponent(status = status)
                }
            }
        }

        if (passthroughDegraded) {
            item { SmallTitle(text = stringResource(R.string.module_section)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    DegradedStatusComponent()
                }
            }
        }

        item { SmallTitle(text = stringResource(R.string.effect_preview)) }
        item {
            FanPreviewCard(
                prefs = prefs,
                prefsRevision = prefsRevision
            )
        }
    }
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
