package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutGroups
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
import top.yukonga.miuix.kmp.icon.extended.AddFolder
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

/**
 * 快捷方式首页（布局定稿 shortcut-groups-settings-layout.md §2/§4，2026-10-25）：
 * 已启用/未启用两区混排单列表——首页顺序=扇形根栏顺序；启用上限 [ShortcutStore.ENABLED_CAP]
 * 固定 5（面板在场最坏容量，不随 gb/vtb 动态），达到后禁止再启用（toast 原因）；
 * 多选收底双动作：组成分组（2~3 普通项预填建组）+ 删除（含组=连成员删，确认文案区分）。
 */
@Composable
internal fun ShortcutListPage(
    prefs: SharedPreferences,
    bar: ShortcutSelectionBar,
    /** 刷新令牌：子页保存/删除/退出时自增——nav3 返回时 remember 保活须靠它重读 */
    revision: Int = 0,
    onEdit: (ShortcutAction) -> Unit,
    onCreateGroup: (List<ShortcutAction>) -> Unit,
    modifier: Modifier = Modifier
) {
    // 内存工作列表（revision 变化重建即重读）：写入时保持"已启用在前、未启用在后"的
    // 分区不变量（列表序=存储序；扇形端按启用序截断）
    var workingList by remember(revision) { mutableStateOf(ShortcutStore.loadUserShortcuts(prefs)) }
    // 批量选择模式（长按行进入）：顶栏变形 + 删除收底（HyperOS 闹钟批量范式）
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun enterSelection(item: ShortcutAction) {
        selectionMode = true
        selectedIds = setOf(item.id)
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
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }

    // ===== 分区（布局 §2.2）=====
    val enabledList = workingList.filter { it.enabled }
    val disabledList = workingList.filter { !it.enabled }
    val enabledCount = enabledList.size
    // 启用上限拦截（布局定稿拍板：固定 5，超了不允许再启用，杜绝候补态）
    fun toggleEnabled(item: ShortcutAction, value: Boolean) {
        if (value && enabledCount >= ShortcutStore.ENABLED_CAP) {
            Toast.makeText(
                context,
                context.getString(R.string.shortcut_enabled_cap_toast, ShortcutStore.ENABLED_CAP),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val rest = workingList.filter { it.id != item.id }
        val updated = item.copy(enabled = value)
        // 开⇒插入已启用区末尾（未启用区之前）；关⇒沉到未启用区末尾（布局 §2.2）
        workingList = if (value) {
            rest.filter { it.enabled } + updated + rest.filter { !it.enabled }
        } else {
            rest + updated
        }
        ShortcutStore.saveUserShortcuts(prefs, workingList)
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        workingList = workingList.filter { it.id !in selectedIds }
        ShortcutStore.saveUserShortcuts(prefs, workingList)
        exitSelection()
    }

    // 组成分组（布局 §4）：仅 2~3 个顶层普通项可用；点击时校验并给原因
    fun createGroupFromSelection() {
        val selected = workingList.filter { it.id in selectedIds }
        val eligible = selected.all { it.kind != ShortcutKind.GROUP && it.kind != ShortcutKind.TOOLBOX }
        if (selected.size in ShortcutGroups.MIN_CHILDREN..ShortcutGroups.MAX_CHILDREN && eligible) {
            onCreateGroup(selected)
            exitSelection()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.batch_group_invalid),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        // 边缘触发区避开系统导航栏：拖到底部时手指落在 nav bar 带上不算进触发区
        scrollThresholdPadding = WindowInsets.systemBars.asPaddingValues(),
        // 库默认触发带太窄（贴屏边才滚），放宽到 120dp——手柄拖拽时手指够不到屏幕边缘
        scrollThreshold = 120.dp
    ) { from, to ->
        val fromIdx = workingList.indexOfFirst { it.id == from.key }
        val toIdx = workingList.indexOfFirst { it.id == to.key }
        // 分区约束（布局 §2.2）：拖动仅限已启用区内部（顺序=扇形根栏序）；
        // 手柄只在已启用行，此处防越界落位到未启用区
        if (fromIdx >= 0 && toIdx >= 0 &&
            workingList[fromIdx].enabled && workingList[toIdx].enabled
        ) {
            workingList = workingList.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        }
    }

    val atStoreCap = ShortcutGroups.storageUnits(workingList) >= ShortcutStore.MAX_STORED_SHORTCUTS
    // 存量超额兜底（新启用已被上限拦死；此卡只服务历史数据/外部写入，不自动禁用）
    val overflow = enabledCount > ShortcutStore.ENABLED_CAP

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            // 选择模式底部预留双动作栏高度，末行不被遮挡
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (selectionMode) 120.dp else 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SmallTitle(
                    text = stringResource(
                        R.string.shortcut_count_format, workingList.size, enabledCount
                    )
                )
            }

            if (overflow) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(
                                R.string.shortcut_overflow_card,
                                enabledCount, ShortcutStore.ENABLED_CAP
                            ),
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            if (workingList.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.shortcut_empty_add_hint),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            if (selectionMode) {
                // 批量=平铺全表（含未启用项可勾选删除）
                itemsIndexed(workingList, key = { _, shortcut -> shortcut.id }) { _, shortcut ->
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
                }
            } else {
                // ===== 已启用区（有序可拖=扇形根栏序）=====
                if (enabledList.isNotEmpty()) {
                    item { SmallTitle(text = stringResource(R.string.shortcut_section_enabled)) }
                    itemsIndexed(enabledList, key = { _, s -> s.id }) { _, shortcut ->
                        ShortcutRow(
                            shortcut = shortcut,
                            showHandle = true,
                            reorderState = reorderState,
                            prefs = prefs,
                            haptics = haptics,
                            onEdit = onEdit,
                            onToggle = ::toggleEnabled,
                            onEnterSelection = ::enterSelection,
                            workingList = workingList
                        )
                    }
                }
                // ===== 未启用区（沉底；无扇形排序柄，保留启停与编辑）=====
                if (disabledList.isNotEmpty()) {
                    item { SmallTitle(text = stringResource(R.string.shortcut_section_disabled)) }
                    itemsIndexed(disabledList, key = { _, s -> s.id }) { _, shortcut ->
                        ShortcutRow(
                            shortcut = shortcut,
                            showHandle = false,
                            reorderState = reorderState,
                            prefs = prefs,
                            haptics = haptics,
                            onEdit = onEdit,
                            onToggle = ::toggleEnabled,
                            onEnterSelection = ::enterSelection,
                            workingList = workingList
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
        }

        if (selectionMode) {
            // 双动作收底（布局 §4）：组成分组 | 删除（HyperOS 批量收底位）
            val deleteEnabled = selectedIds.isNotEmpty()
            val deleteTint = if (deleteEnabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.sinkClickable { createGroupFromSelection() }
                ) {
                    Icon(
                        imageVector = MiuixIcons.AddFolder,
                        contentDescription = stringResource(R.string.batch_create_group),
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = stringResource(R.string.batch_create_group),
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.footnote1
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.sinkClickable(enabled = deleteEnabled) { showDeleteConfirm = true }
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
    }

    // 批量删除确认（HyperOS 批量模式惯例：破坏性动作确认，删除为红色）——
    // §2.5 WindowDialog 定案模式：两等宽 TextButton + Spacer(20dp)。
    // 含组时文案区分（布局 §4：删组=连成员一起删，和解散不是同义词）
    val selectedForDialog = workingList.filter { it.id in selectedIds }
    val groupsInDialog = selectedForDialog.filter { it.kind == ShortcutKind.GROUP }
    WindowDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.shortcut_delete),
        summary = if (groupsInDialog.isNotEmpty()) {
            stringResource(
                R.string.shortcut_delete_confirm_with_groups,
                selectedForDialog.size,
                groupsInDialog.size,
                groupsInDialog.sumOf { it.children?.size ?: 0 }
            )
        } else {
            stringResource(R.string.shortcut_batch_delete_confirm, selectedForDialog.size)
        },
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

/**
 * 普通行（两区共用）：单击=编辑（禁用项也可编辑，布局 §2.2"禁用不阻止单击"）；
 * 长按=进批量选择；开关按分区规则落位；拖动手柄仅已启用区（未启用区无扇形序意义）。
 * 禁用变暗用 alpha 表达（BasicComponent 的 enabled 会连带挡交互，与"可单击编辑"冲突）。
 */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.ShortcutRow(
    shortcut: ShortcutAction,
    showHandle: Boolean,
    reorderState: sh.calvin.reorderable.ReorderableLazyListState,
    prefs: SharedPreferences,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onEdit: (ShortcutAction) -> Unit,
    onToggle: (ShortcutAction, Boolean) -> Unit,
    onEnterSelection: (ShortcutAction) -> Unit,
    workingList: List<ShortcutAction>
) {
    val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
    // 行体（带 ReorderableCollectionItemScope 接收者：draggableHandle 需要它在作用域内）
    val content: @Composable sh.calvin.reorderable.ReorderableCollectionItemScope.(Boolean) -> Unit =
        { isDragging ->
        // 拖动中的抬升感：占位方块无卡底，阴影不可见，用微放大表达"浮起"
        val scale by animateFloatAsState(
            if (isDragging) 1.03f else 1f, label = "shortcutDragScale"
        )
        BasicComponent(
            title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
            summary = buildShortcutSummary(
                shortcut,
                stringResource(R.string.shortcut_uri_unset),
                stringResource(R.string.shortcut_toolbox_desc)
            ),
            startAction = if (iconPkg != null) {
                { SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f) }
            } else null,
            endActions = {
                // 行内启停：即时写盘即时按分区规则落位
                Switch(
                    checked = shortcut.enabled,
                    onCheckedChange = { onToggle(shortcut, it) }
                )
                if (showHandle) {
                    // 拖动手柄：40dp 触摸热区（图标视觉 24dp）；松手一次落盘。
                    // suppressRowLongPress 先消费 down——手柄按下不得漏给行内长按
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(40.dp)
                            .suppressRowLongPress()
                            .draggableHandle(
                                onDragStarted = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    ShortcutStore.saveUserShortcuts(prefs, workingList)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Sort,
                            contentDescription = stringResource(R.string.shortcut_drag_handle),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            },
            modifier = Modifier.animateItem()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .alpha(if (shortcut.enabled) 1f else 0.55f)
                // 单击与长按必须同一节点裁决（combinedClickable）：
                // foundation 1.10 的 node clickable 先消费 DOWN，外挂
                // detectTapGestures 的 awaitFirstDown 只认未消费 down，
                // 长按检测永远收不到手势（批量模式失效根因）。
                .combinedClickable(
                    onClick = { onEdit(shortcut) },
                    onLongClick = { onEnterSelection(shortcut) }
                )
        )
    }
    // 统一包 ReorderableItem：content 需要其 scope 接收者（draggableHandle）；
    // 未启用行无手柄、注册进 reorder 状态无副作用
    ReorderableItem(
        state = reorderState,
        key = shortcut.id,
        animateItemModifier = Modifier.animateItem()
    ) { isDragging -> content(isDragging) }
}

/** 手柄区按下即消费 down：掐死行 combinedClickable 的长按计时——批量选择只能从手柄以外的行体进入。 */
private fun Modifier.suppressRowLongPress(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
}

@Composable
internal fun SelectionCircle(checked: Boolean) {
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
        // 组摘要：成员 label 串（N 项）——与组编辑页的成员展示同语义
        ShortcutKind.GROUP -> {
            val n = shortcut.children?.size ?: 0
            val labels = shortcut.children.orEmpty().joinToString("、") { it.label }
            "${n}项：$labels"
        }
    }
    return if (raw.length > 40) raw.take(38) + "…" else raw
}
