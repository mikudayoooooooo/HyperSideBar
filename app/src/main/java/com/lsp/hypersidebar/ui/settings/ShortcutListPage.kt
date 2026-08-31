package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ShortcutListPage(
    prefs: SharedPreferences,
    onEdit: (ShortcutAction) -> Unit,
    onAdd: (ShortcutKind) -> Unit,
    modifier: Modifier = Modifier
) {
    // 本页内排序变化即时刷新（保存/删除由弹栈重组合自然刷新，§2.1 迁移后无外部 revision）
    var listRevision by remember { mutableIntStateOf(0) }
    val shortcuts = remember(prefs, listRevision) {
        ShortcutStore.loadUserShortcuts(prefs)
    }
    val enabledCount = shortcuts.count { it.enabled }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SmallTitle(
                text = stringResource(
                    R.string.shortcut_count_format, shortcuts.size, enabledCount
                )
            )
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
            itemsIndexed(shortcuts, key = { _, shortcut -> shortcut.id }) { index, shortcut ->
                val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
                val status = if (shortcut.enabled) "" else stringResource(R.string.shortcut_disabled_tag)
                BasicComponent(
                    title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
                    summary = buildShortcutSummary(
                        shortcut,
                        stringResource(R.string.shortcut_uri_unset),
                        stringResource(R.string.shortcut_toolbox_desc)
                    ) + status,
                    startAction = if (iconPkg != null) {
                        { SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f) }
                    } else null,
                    endActions = {
                        // 排序（§2.4 解耦）：列表序=扇形展示序，上/下移即时写盘
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    ShortcutStore.moveShortcut(prefs, index, index - 1)
                                    listRevision++
                                }
                            },
                            enabled = index > 0
                        ) {
                            Icon(
                                imageVector = MiuixIcons.ExpandLess,
                                contentDescription = stringResource(R.string.shortcut_move_up),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (index < shortcuts.lastIndex) {
                                    ShortcutStore.moveShortcut(prefs, index, index + 1)
                                    listRevision++
                                }
                            },
                            enabled = index < shortcuts.lastIndex
                        ) {
                            Icon(
                                imageVector = MiuixIcons.ExpandMore,
                                contentDescription = stringResource(R.string.shortcut_move_down),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    },
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
                        onClick = { onAdd(ShortcutKind.COMPONENT) }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_intent_uri),
                        summary = stringResource(R.string.shortcuts_add_intent_uri_summary),
                        onClick = { onAdd(ShortcutKind.INTENT_URI) }
                    )
                }
            }
        }

        // 启用项超出扇形展示上限时提示（存储无上限，§2.4 解耦）
        if (enabledCount > ShortcutStore.MAX_USER_SHORTCUTS) {
            item {
                Text(
                    text = stringResource(
                        R.string.shortcut_display_overflow, enabledCount, ShortcutStore.MAX_USER_SHORTCUTS
                    ),
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
