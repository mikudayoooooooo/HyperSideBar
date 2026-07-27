package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.theme.LocalSemanticColors
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SCOPE_PACKAGE = "com.miui.securitycenter"

@Composable
internal fun HomePage(
    prefs: SharedPreferences,
    prefsRevision: Int,
    status: ModuleStatus,
    service: XposedService?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by remember(prefs, prefsRevision) {
        mutableStateOf(prefs.getBoolean(PrefKeys.ENABLED, true))
    }
    val frameworkName by produceState(
        initialValue = context.getString(R.string.unknown),
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkName?.toString() }.getOrNull()
                ?: context.getString(R.string.unknown)
        }
    }
    val frameworkVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.frameworkVersion?.toString() }.getOrNull() ?: "--"
        }
    }
    val apiVersion by produceState(
        initialValue = "--",
        key1 = service
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { service?.apiVersion?.toString() }.getOrNull() ?: "--"
        }
    }


    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.module_section)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                ModuleStatusComponent(
                    status = status,
                    onClick = if (status == ModuleStatus.ACTIVE) null else {
                        { openLsposedManager(context) }
                    }
                )
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

        item { SmallTitle(text = stringResource(R.string.framework_info)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    BasicComponent(
                        title = stringResource(R.string.framework_name),
                        summary = frameworkName
                    )
                    BasicComponent(
                        title = stringResource(R.string.framework_version),
                        summary = frameworkVersion
                    )
                    BasicComponent(
                        title = stringResource(R.string.api_version),
                        summary = apiVersion
                    )
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
private fun ModuleStatusComponent(
    status: ModuleStatus,
    onClick: (() -> Unit)?
) {
    val semantic = LocalSemanticColors.current
    val error = MiuixTheme.colorScheme.error
    val (accent, title, summary) = when (status) {
        ModuleStatus.ACTIVE -> Triple(
            semantic.success,
            stringResource(R.string.module_active),
            stringResource(R.string.module_active_summary)
        )
        ModuleStatus.INACTIVE -> Triple(
            error,
            stringResource(R.string.module_inactive),
            stringResource(R.string.deactivation_hint)
        )
        ModuleStatus.UNKNOWN -> Triple(
            MiuixTheme.colorScheme.onSurfaceVariantSummary,
            stringResource(R.string.checking_module),
            stringResource(R.string.checking_module_summary)
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
        },
        onClick = onClick
    )
}
