package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutGroups
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 添加页（布局定稿 §3，2026-10-25）：首页右上角＋进入的中间页，四类入口集中于此；
 * 首页底部的添加段已移除。前三类替换本页进入现有新建流程，组入口进组详情。
 */
@Composable
internal fun ShortcutAddPage(
    prefs: SharedPreferences,
    onAddKind: (ShortcutKind) -> Unit,
    onAddGroup: () -> Unit
) {
    val list = remember { ShortcutStore.loadUserShortcuts(prefs) }
    val atStoreCap = ShortcutGroups.storageUnits(list) >= ShortcutStore.MAX_STORED_SHORTCUTS
    // 可入组候选=顶层普通项（非组非工具箱）；不足 2 个或存储满 ⇒ 组入口禁用并给原因
    val eligible = list.count { it.kind != ShortcutKind.GROUP && it.kind != ShortcutKind.TOOLBOX }
    val groupBlockedReason = when {
        atStoreCap -> stringResource(R.string.shortcuts_at_store_limit, ShortcutStore.MAX_STORED_SHORTCUTS)
        eligible < 2 -> stringResource(R.string.batch_group_invalid)
        else -> null
    }

    LazyColumn(
        modifier = Modifier.overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SmallTitle(text = stringResource(R.string.shortcut_add_title)) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_component),
                        summary = stringResource(R.string.shortcuts_add_component_summary),
                        onClick = { onAddKind(ShortcutKind.COMPONENT) },
                        enabled = !atStoreCap
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_intent_uri),
                        summary = stringResource(R.string.shortcuts_add_intent_uri_summary),
                        onClick = { onAddKind(ShortcutKind.INTENT_URI) },
                        enabled = !atStoreCap
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_qs_tile),
                        summary = stringResource(R.string.shortcuts_add_qs_tile_summary),
                        onClick = { onAddKind(ShortcutKind.QS_TILE) },
                        enabled = !atStoreCap
                    )
                    ArrowPreference(
                        title = stringResource(R.string.shortcuts_add_group),
                        summary = groupBlockedReason
                            ?: stringResource(R.string.shortcuts_add_group_summary),
                        onClick = { onAddGroup() },
                        enabled = groupBlockedReason == null
                    )
                }
            }
        }
        if (atStoreCap) {
            item {
                Text(
                    text = stringResource(
                        R.string.shortcuts_at_store_limit, ShortcutStore.MAX_STORED_SHORTCUTS
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}
