package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
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
    // 内存工作列表：拖动交换/启停只改内存并即时写盘（列表序=扇形展示序）；
    // 进出页面（弹栈重组）remember 重建即重新加载
    var workingList by remember { mutableStateOf(ShortcutStore.loadUserShortcuts(prefs)) }
    // 批量选择模式（长按行进入）：勾选后顶部栏删除
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleEnabled(item: ShortcutAction, value: Boolean) {
        workingList = workingList.map {
            if (it.id == item.id) it.copy(enabled = value) else it
        }
        ShortcutStore.updateShortcut(prefs, item.copy(enabled = value))
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        workingList = workingList.filter { it.id !in selectedIds }
        ShortcutStore.saveUserShortcuts(prefs, workingList)
        exitSelection()
    }

    val listState = rememberLazyListState()
    val dragState = rememberDragReorderState(
        listState = listState,
        onMoveByKey = { fromKey, toKey ->
            val from = workingList.indexOfFirst { it.id == fromKey }
            val to = workingList.indexOfFirst { it.id == toKey }
            if (from >= 0 && to >= 0) {
                workingList = workingList.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        onDragFinished = { ShortcutStore.saveUserShortcuts(prefs, workingList) }
    )

    val enabledCount = workingList.count { it.enabled }
    val atStoreCap = workingList.size >= ShortcutStore.MAX_STORED_SHORTCUTS

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectionMode) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.shortcut_selected_count, selectedIds.size),
                            style = MiuixTheme.textStyles.body1,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.layout_sheet_cancel),
                            onClick = { exitSelection() }
                        )
                        TextButton(
                            text = stringResource(R.string.shortcut_batch_delete, selectedIds.size),
                            onClick = { deleteSelected() }
                        )
                    }
                }
            }
        } else {
            item {
                SmallTitle(
                    text = stringResource(
                        R.string.shortcut_count_format, workingList.size, enabledCount
                    )
                )
            }
        }

        if (workingList.isEmpty()) {
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

        if (workingList.isNotEmpty()) {
            itemsIndexed(workingList, key = { _, shortcut -> shortcut.id }) { _, shortcut ->
                if (selectionMode) {
                    val checked = shortcut.id in selectedIds
                    BasicComponent(
                        title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
                        summary = buildShortcutSummary(
                            shortcut,
                            stringResource(R.string.shortcut_uri_unset),
                            stringResource(R.string.shortcut_toolbox_desc)
                        ),
                        startAction = {
                            Checkbox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    selectedIds = if (checked) selectedIds - shortcut.id
                                    else selectedIds + shortcut.id
                                }
                            )
                        },
                        onClick = {
                            selectedIds = if (checked) selectedIds - shortcut.id
                            else selectedIds + shortcut.id
                        },
                        modifier = Modifier.animateItem()
                    )
                } else {
                    val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
                    BasicComponent(
                        title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
                        summary = buildShortcutSummary(
                            shortcut,
                            stringResource(R.string.shortcut_uri_unset),
                            stringResource(R.string.shortcut_toolbox_desc)
                        ),
                        // 禁用项整行变暗（§2.4 用户拍板），不再加文字标签
                        enabled = shortcut.enabled,
                        startAction = if (iconPkg != null) {
                            { SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f) }
                        } else null,
                        endActions = {
                            // 行内启停（§2.4 用户拍板）：即时写盘即时变暗，无需进编辑页
                            Switch(
                                checked = shortcut.enabled,
                                onCheckedChange = { toggleEnabled(shortcut, it) }
                            )
                            Icon(
                                imageVector = MiuixIcons.Sort,
                                contentDescription = stringResource(R.string.shortcut_drag_handle),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(24.dp)
                                    .dragReorderHandle(dragState, shortcut.id)
                            )
                        },
                        onClick = { onEdit(shortcut) },
                        modifier = Modifier
                            .dragReorderItem(dragState, shortcut.id)
                            .animateItem()
                            // 长按进入批量选择模式
                            .pointerInput(shortcut.id) {
                                detectTapGestures(
                                    onLongPress = {
                                        selectionMode = true
                                        selectedIds = setOf(shortcut.id)
                                    }
                                )
                            }
                    )
                }
            }
        }

        if (!selectionMode) {
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
                            enabled = !atStoreCap
                        )
                        ArrowPreference(
                            title = stringResource(R.string.shortcuts_add_intent_uri),
                            summary = stringResource(R.string.shortcuts_add_intent_uri_summary),
                            onClick = { onAdd(ShortcutKind.INTENT_URI) },
                            enabled = !atStoreCap
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // 启用项超出扇形展示上限时提示（存储上限 10，§2.4 解耦）
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
}

/** 列表摘要：超长截断（组件名/intent URI 动辄上百字符，统一观感）。 */
private fun buildShortcutSummary(shortcut: ShortcutAction, unsetUri: String, toolboxLabel: String): String {
    val raw = when (shortcut.kind) {
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
    return if (raw.length > 40) raw.take(38) + "…" else raw
}
