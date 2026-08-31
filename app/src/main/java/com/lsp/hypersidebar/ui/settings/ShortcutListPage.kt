package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ShortcutListPage(
    prefs: SharedPreferences,
    revision: Int,
    onEdit: (ShortcutAction) -> Unit,
    onAdd: (ShortcutKind) -> Unit,
    modifier: Modifier = Modifier
) {
    val shortcuts = remember(prefs, revision) {
        ShortcutStore.loadUserShortcuts(prefs)
    }
    val isFull = shortcuts.size >= ShortcutStore.MAX_USER_SHORTCUTS

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SmallTitle(text = stringResource(R.string.shortcut_count_format, shortcuts.size, ShortcutStore.MAX_USER_SHORTCUTS))
        }

        if (shortcuts.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.shortcuts_empty_hint),
                        modifier = Modifier.padding(16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        if (shortcuts.isNotEmpty()) {
            items(shortcuts, key = { it.id }) { shortcut ->
                val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
                val status = if (shortcut.enabled) "" else stringResource(R.string.shortcut_disabled_tag)
                ArrowPreference(
                    title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
                    summary = buildShortcutSummary(
                        shortcut,
                        stringResource(R.string.shortcut_uri_unset),
                        stringResource(R.string.shortcut_toolbox_desc)
                    ) + status,
                    startAction = if (iconPkg != null) {
                        { SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f) }
                    } else null,
                    onClick = { onEdit(shortcut) }
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.shortcuts_add_section))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_component),
                        summary = stringResource(R.string.shortcuts_add_component_summary),
                        onClick = { onAdd(ShortcutKind.COMPONENT) },
                        enabled = !isFull
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_intent_uri),
                        summary = stringResource(R.string.shortcuts_add_intent_uri_summary),
                        onClick = { onAdd(ShortcutKind.INTENT_URI) },
                        enabled = !isFull
                    )
                }
            }
        }

        if (isFull) {
            item {
                Text(
                    text = stringResource(R.string.shortcuts_at_limit, ShortcutStore.MAX_USER_SHORTCUTS),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun buildShortcutSummary(shortcut: ShortcutAction, unsetUri: String, toolboxLabel: String) = when (shortcut.kind) {
    ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> {
        val pkg = shortcut.packageName ?: "?"
        val act = shortcut.activityName ?: "?"
        "$pkg/$act"
    }
    ShortcutKind.INTENT_URI -> shortcut.intentUri ?: unsetUri
    ShortcutKind.TOOLBOX -> toolboxLabel
    ShortcutKind.SERVICE -> {
        val pkg = shortcut.packageName ?: "?"
        val svc = shortcut.serviceName ?: "?"
        "$pkg/$svc"
    }
}
