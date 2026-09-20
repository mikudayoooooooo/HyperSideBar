package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 批量选择 ↔ 顶栏桥（页面写 / MainScreen 顶栏读）：HyperOS 批量模式=顶栏变形
 * （✕ 退出 + 居中"已选 N 项" + 全选图标），而顶栏归 MainScreen 所有——
 * 选择态经此共享。lambda 由页面每次重组后刷新（SideEffect），无陈旧捕获。
 */
internal class ShortcutSelectionBar {
    var active by mutableStateOf(false)
    var count by mutableStateOf(0)
    var allSelected by mutableStateOf(false)
    var onExit: (() -> Unit)? = null
    var onToggleAll: (() -> Unit)? = null
}

@Composable
internal fun ShortcutListPage(
    prefs: SharedPreferences,
    bar: ShortcutSelectionBar,
    onEdit: (ShortcutAction) -> Unit,
    onAdd: (ShortcutKind) -> Unit,
    modifier: Modifier = Modifier
) {
    // 内存工作列表：拖动交换/启停只改内存并即时写盘（列表序=扇形展示序）；
    // 进出页面（弹栈重组）remember 重建即重新加载
    var workingList by remember { mutableStateOf(ShortcutStore.loadUserShortcuts(prefs)) }
    // 批量选择模式（长按行进入）：顶栏变形 + 删除收底（HyperOS 闹钟批量范式）
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    // 顶栏桥同步（组合后写，值稳定不触发多余重组）；弹栈时清桥防顶栏残留选择态
    SideEffect {
        bar.active = selectionMode
        bar.count = selectedIds.size
        bar.allSelected = selectionMode && workingList.isNotEmpty() && selectedIds.size == workingList.size
        bar.onExit = { exitSelection() }
        bar.onToggleAll = {
            selectedIds = if (bar.allSelected) emptySet() else workingList.map { it.id }.toSet()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            bar.active = false
            bar.onExit = null
            bar.onToggleAll = null
        }
    }
    // 选择模式下系统返回=退出选择（不弹页）
    BackHandler(enabled = selectionMode) { exitSelection() }

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

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            // 选择模式底部预留删除栏高度，末行不被遮挡
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (selectionMode) 120.dp else 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!selectionMode) {
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
                            // HyperOS 批量范式：勾选圈在行尾（未选中=空心环，选中=蓝底对勾）
                            endActions = { SelectionCircle(checked = checked) },
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
                                        .dragReorderHandleImmediate(dragState, shortcut.id)
                                )
                            },
                            modifier = Modifier
                                .dragReorderItem(dragState, shortcut.id)
                                .animateItem()
                                // 单击与长按必须同一节点裁决（combinedClickable）：
                                // foundation 1.10 的 node clickable 先消费 DOWN，外挂
                                // detectTapGestures 的 awaitFirstDown 只认未消费 down，
                                // 长按检测永远收不到手势（批量模式失效根因）。
                                // 禁用行仍可长按入选（批量删除禁用项），仅挡单击。
                                .combinedClickable(
                                    onClick = { if (shortcut.enabled) onEdit(shortcut) },
                                    onLongClick = {
                                        selectionMode = true
                                        selectedIds = setOf(shortcut.id)
                                    }
                                )
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
                            // C3（批次 3）：快捷开关磁贴入口（用户 2026-09-05：与上两项同级；
                            // 将来 ShortcutManager 型应用快捷方式并入同一选择器）
                            ArrowPreference(
                                title = stringResource(R.string.shortcuts_add_qs_tile),
                                summary = stringResource(R.string.shortcuts_add_qs_tile_summary),
                                onClick = { onAdd(ShortcutKind.QS_TILE) },
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

        if (selectionMode) {
            // HyperOS 闹钟批量范式：删除收底（居中垃圾桶+文字，未选中变灰不可点）
            val deleteEnabled = selectedIds.isNotEmpty()
            val deleteTint = if (deleteEnabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .sinkClickable(enabled = deleteEnabled) { showDeleteConfirm = true }
                    .padding(horizontal = 32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.shortcut_delete),
                    tint = deleteTint,
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = stringResource(R.string.shortcut_delete),
                    color = deleteTint,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
    }

    // 批量删除确认（HyperOS 批量模式惯例：破坏性动作确认，删除为红色）——
    // §2.5 WindowDialog 定案模式：两等宽 TextButton + Spacer(20dp)
    WindowDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.shortcut_delete),
        summary = stringResource(R.string.shortcut_batch_delete_confirm, selectedIds.size),
        onDismissRequest = { showDeleteConfirm = false },
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.layout_sheet_cancel),
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.shortcut_delete),
                    onClick = {
                        showDeleteConfirm = false
                        deleteSelected()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.error)
                )
            }
        }
    )
}

/** HyperOS 批量勾选圈：未选中=空心环，选中=主题蓝底+对勾（miuix Checkbox 无空心态）。 */
@Composable
private fun SelectionCircle(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                if (checked) MiuixTheme.colorScheme.primary else Color.Transparent,
                CircleShape
            )
            .border(
                width = 1.5.dp,
                color = if (checked) Color.Transparent else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
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
        ShortcutKind.QS_TILE -> {
            val pkg = shortcut.packageName ?: "?"
            val svc = shortcut.serviceName ?: "?"
            "$pkg/$svc"
        }
        ShortcutKind.SHORTCUT_ID -> {
            val pkg = shortcut.packageName ?: "?"
            "$pkg/#${shortcut.shortcutId ?: "?"}"
        }
    }
    return if (raw.length > 40) raw.take(38) + "…" else raw
}
