package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.HLog
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutGroups
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** 禁用行只淡化图标与文字（开关保持全强度，避免"整行失效"的误读）。 */
private const val DISABLED_ROW_ALPHA = 0.55f

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
    /** 顶栏 ＋：打开添加 sheet（添加页已取消） */
    var onAdd: (() -> Unit)? = null
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
    // ===== 添加 sheet（三步：入口 → 选择器 → 草稿）=====
    var showAddSheet by remember { mutableStateOf(false) }
    var addStep by remember { mutableStateOf(0) }            // 0 入口 / 1 选择器 / 2 草稿
    var addTab by remember { mutableStateOf(0) }             // 选择器步骤用：0 组件 / 1 快捷方式（磁贴+应用快捷方式）
    var draftShortcut by remember { mutableStateOf<ShortcutAction?>(null) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    /**
     * 选中目标 → 进入 sheet 内的草稿步骤（名称/测试/保存都在这里，不跳页）。
     * 跳页会叠两套动画、且丢失 sheet 上下文，用户反馈"有点奇怪"。
     */
    fun toDraft(action: ShortcutAction) {
        HLog.i("AddShortcut", "toDraft kind=${action.kind} label='${action.label}'")
        draftShortcut = action
        addStep = 2
    }

    fun componentAction(target: SplitResult, itemLabel: String) = ShortcutAction(
        id = UUID.randomUUID().toString(),
        kind = ShortcutKind.COMPONENT,
        label = itemLabel,
        enabled = true,
        packageName = target.pkg,
        activityName = target.act,
        iconPackageName = target.pkg
    )

    fun tileAction(info: QsTileInfo, isTile: Boolean): ShortcutAction {
        val dynamic = !isTile && info.shortcutId != null
        return ShortcutAction(
            id = UUID.randomUUID().toString(),
            kind = when {
                isTile -> ShortcutKind.QS_TILE
                dynamic -> ShortcutKind.SHORTCUT_ID
                else -> ShortcutKind.COMPONENT
            },
            label = info.label,
            enabled = true,
            packageName = info.packageName,
            activityName = if (isTile || dynamic) null else info.className,
            serviceName = if (isTile) info.className else null,
            shortcutId = if (dynamic) info.shortcutId else null,
            iconPackageName = info.packageName
        )
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun enterSelection(item: ShortcutAction) {
        selectionMode = true
        selectedIds = setOf(item.id)
    }

    /** 批量态行点击 = 勾选/取消勾选（与普通态"点击进编辑"互斥，由 selecting 门裁决）。 */
    fun toggleSelected(item: ShortcutAction) {
        selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
    }

    // 顶栏桥同步（组合后写，值稳定不触发多余重组）；弹栈时清桥防顶栏残留选择态
    SideEffect {
        bar.active = selectionMode
        bar.count = selectedIds.size
        bar.allSelected = selectionMode && workingList.isNotEmpty() && selectedIds.size == workingList.size
        bar.onExit = { exitSelection() }
        bar.onAdd = {
            // 每次打开都从干净状态开始
            addStep = 0
            addTab = 0
            draftShortcut = null
            showAddSheet = true
        }
        bar.onToggleAll = {
            selectedIds = if (bar.allSelected) emptySet() else workingList.map { it.id }.toSet()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            bar.active = false
            bar.onExit = null
            bar.onAdd = null
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
                .settingsScroll(),
            // 选择模式底部预留双动作栏高度，末行不被遮挡
            contentPadding = PaddingValues(
                start = SettingsDimens.ScreenInset,
                end = SettingsDimens.ScreenInset,
                top = SettingsDimens.ListTop,
                bottom = if (selectionMode) 120.dp else SettingsDimens.ListBottom
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionTitle(
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

            // ===== 已启用区（有序可拖=扇形根栏序）=====
            // 批量态**不换行形态**：同一批 ShortcutRow 只把行尾换成勾选圈、Switch 置灰、点击改成勾选。
            // （旧实现整表换成 BasicComponent 平铺 → 行高/分区标题/手柄都变，animateItem 把它演成"跳到别处"）
            if (enabledList.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.shortcut_section_enabled)) }
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
                        workingList = workingList,
                        selected = selectionMode && shortcut.id in selectedIds,
                        selecting = selectionMode,
                        onToggleSelected = ::toggleSelected
                    )
                }
            }
            // ===== 未启用区（沉底；无扇形排序柄，保留启停与编辑）=====
            if (disabledList.isNotEmpty()) {
                item { SectionTitle(text = stringResource(R.string.shortcut_section_disabled)) }
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
                        workingList = workingList,
                        selected = selectionMode && shortcut.id in selectedIds,
                        selecting = selectionMode,
                        onToggleSelected = ::toggleSelected
                    )
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
            // 双动作收底（布局 §4）：组成分组 | 删除——FloatingToolbar 承载（自带点击吞没，
            // 不再需要手写 padding/windowInsetsPadding；父 Scaffold 已消费系统栏 insets）
            val deleteEnabled = selectedIds.isNotEmpty()
            val deleteTint = if (deleteEnabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            }
            FloatingToolbar(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(56.dp),
                    verticalAlignment = Alignment.CenterVertically
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
            ConfirmCancelRow(
                confirmText = stringResource(R.string.shortcut_delete),
                onConfirm = {
                    showDeleteConfirm = false
                    deleteSelected()
                },
                onCancel = { showDeleteConfirm = false },
                danger = true
            )
        }
    )

    val draftBar = remember { ShortcutEditBar() }

    AddShortcutSheet(
        show = showAddSheet,
        onDismiss = {
            showAddSheet = false
            addStep = 0
            draftShortcut = null
        },
        step = addStep,
        onStepChange = { next ->
            addStep = next
            if (next == 0) draftShortcut = null
        },
        onPickEntry = { tab ->
            // Intent 没有可选的"目标列表"，直接进草稿（URI 就在草稿里填）
            if (tab == 1) {
                toDraft(
                    ShortcutAction(
                        id = UUID.randomUUID().toString(),
                        kind = ShortcutKind.INTENT_URI,
                        label = "",
                        enabled = true
                    )
                )
            } else {
                addTab = tab
                addStep = 1
            }
        },
        // 组件 / 磁贴 / 应用快捷方式：选中即进草稿步骤
        addTab = addTab,
        onPickComponent = { target, itemLabel -> toDraft(componentAction(target, itemLabel)) },
        onPickTile = { info, isTile -> toDraft(tileAction(info, isTile)) },
        atStoreCap = atStoreCap,
        draft = draftShortcut,
        draftBar = draftBar,
        prefs = prefs,
        onSaved = { updated ->
            HLog.i("AddShortcut", "draft saved: kind=${updated.kind} label='${updated.label}'")
            val decision = ShortcutStore.decideAdd(
                candidate = updated,
                enabledCount = enabledCount,
                storageUnits = ShortcutGroups.storageUnits(workingList)
            )
            if (decision is ShortcutStore.AddDecision.RejectedStorageFull ||
                (decision is ShortcutStore.AddDecision.Save &&
                    !ShortcutStore.addShortcut(prefs, decision.item))
            ) {
                // 存储上限：保留 sheet 与草稿，否则用户填完一整轮却什么都没存下
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.shortcuts_at_store_limit,
                        ShortcutStore.MAX_STORED_SHORTCUTS
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                if ((decision as ShortcutStore.AddDecision.Save).disabledByCap) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.shortcut_enabled_cap_toast,
                            ShortcutStore.ENABLED_CAP
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                workingList = ShortcutStore.loadUserShortcuts(prefs)
                draftShortcut = null
                addStep = 0
                showAddSheet = false
            }
        }
    )
}

/**
 * 添加快捷方式 BottomSheet：
 * 首屏是三个入口行（与已删除的 ShortcutAddPage 同形态），点进去才展开对应选择器；
 * 选择器步骤里左上 ← 回入口、右上 ✓ 进入添加页（命名 / 测试 / 启用都在那一页做）。
 *
 * 只有 3 个入口：磁贴与应用快捷方式本来就同属一个选择器（QsTilePickerPane 内含两组 chip）。
 */
@Composable
private fun AddShortcutSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    step: Int,
    onStepChange: (Int) -> Unit,
    addTab: Int,
    onPickEntry: (Int) -> Unit,
    onPickComponent: (SplitResult, String) -> Unit,
    onPickTile: (QsTileInfo, Boolean) -> Unit,
    atStoreCap: Boolean,
    draft: ShortcutAction?,
    draftBar: ShortcutEditBar,
    prefs: SharedPreferences,
    onSaved: (ShortcutAction) -> Unit
) {
    // 内容区高度对齐 LayoutBottomSheet：屏高 3/4 − 标题栏（sheet 整体约 3/4 屏）
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 3 / 4 - 56).dp
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.shortcut_add_title),
        startAction = {
            if (step == 0) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.layout_sheet_cancel),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
            } else {
                IconButton(onClick = { onStepChange(0) }) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.back),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        },
        endAction = {
            // 草稿步骤：保存放顶栏（与编辑页一致），表单内只留「测试启动」
            if (step == 2) {
                IconButton(onClick = { draftBar.onSave?.invoke() }) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Check,
                        contentDescription = stringResource(R.string.layout_sheet_save),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
            }
        },
        // 第 1 步（选择器）里系统返回 / 点外部 → 回到入口列表，而不是直接关掉 sheet
        onDismissRequest = { if (step == 0) onDismiss() else onStepChange(0) },
        content = {
            // 入口步骤贴合内容高度；进入选择器/草稿后固定 3/4 屏（一次流程只变一次高）。
            // animateContentSize 把这次变高做成动画：miuix 的 sheet 只有显隐位移动画，
            // 内容高度变化是逐帧重新测量而来，不加它就会"啪"地跳到新高度。
            val sheetHeight = if (step == 0) Modifier else Modifier.height(contentMaxHeight)
            Column(
                Modifier
                    .animateContentSize(
                        animationSpec = tween(
                            durationMillis = 260,
                            easing = FastOutSlowInEasing
                        )
                    )
                    .then(sheetHeight)
            ) {
                if (step == 0) {
                    // 入口行：与 ShortcutAddPage 的观感一致（标题 + 说明 + 箭头）
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SettingsDimens.ScreenInset,
                                end = SettingsDimens.ScreenInset,
                                top = SettingsDimens.SectionGap
                            )
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.shortcut_add_tab_component),
                            onClick = { onPickEntry(0) },
                            enabled = !atStoreCap
                        )
                        ArrowPreference(
                            title = stringResource(R.string.shortcut_add_tab_intent),
                            onClick = { onPickEntry(1) },
                            enabled = !atStoreCap
                        )
                        ArrowPreference(
                            title = stringResource(R.string.shortcut_add_tab_shortcut),
                            onClick = { onPickEntry(2) },
                            enabled = !atStoreCap
                        )
                    }
                    if (atStoreCap) {
                        Text(
                            text = stringResource(
                                R.string.shortcuts_at_store_limit,
                                ShortcutStore.MAX_STORED_SHORTCUTS
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(
                                horizontal = SettingsDimens.CardInner,
                                vertical = SettingsDimens.SectionGap
                            )
                        )
                    }
                } else if (step == 1) {
                    // 选择器占满固定高度（父 Column 已定高）
                    Box(Modifier.fillMaxSize()) {
                        when (addTab) {
                            0 -> ActivityPickerPane(
                                onSelected = { pkg, act, itemLabel, _ ->
                                    onPickComponent(SplitResult(pkg, act), itemLabel)
                                },
                                handleSystemBack = false,
                                enablePullToRefresh = false
                            )
                            else -> QsTilePickerPane(
                                onSelected = { info, isTile -> onPickTile(info, isTile) },
                                handleSystemBack = false,
                                enablePullToRefresh = false
                            )
                        }
                    }
                } else if (draft != null) {
                    // 草稿步骤：复用编辑页的紧凑表单；保存走 sheet 顶栏（draftBar.onSave）
                    ShortcutEditPage(
                        shortcut = draft,
                        isNew = true,
                        prefs = prefs,
                        bar = draftBar,
                        initialTargetSpec = SplitResult(
                            pkg = draft.packageName ?: "",
                            act = draft.activityName ?: ""
                        ),
                        onSave = onSaved,
                        onDelete = null,
                        onPickActivity = {},
                        onPickQsTile = {},
                        onBack = {},
                        showPickButtons = false
                    )
                }
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
    workingList: List<ShortcutAction>,
    /** 批量选择态下该项是否已选：写入语义树，读屏才能播报选中状态（原先只有圆圈是视觉的） */
    selected: Boolean = false,
    /** 批量选择态：行形态保持不变，只换行尾（勾选圈）并关掉 Switch/拖动/编辑入口 */
    selecting: Boolean = false,
    /** 批量态行点击：勾选/取消勾选 */
    onToggleSelected: (ShortcutAction) -> Unit = {}
) {
    val iconPkg = shortcut.iconPackageName ?: shortcut.packageName
    // 行体（带 ReorderableCollectionItemScope 接收者：draggableHandle 需要它在作用域内）
    val content: @Composable sh.calvin.reorderable.ReorderableCollectionItemScope.(Boolean) -> Unit =
        { isDragging ->
        // 拖动中的抬升感：占位方块无卡底，阴影不可见，用微放大表达"浮起"
        val scale by animateFloatAsState(
            if (isDragging) 1.03f else 1f, label = "shortcutDragScale"
        )
        // 禁用态只淡"图标 + 文字"：整行 alpha 会连开关一起变灰，看起来像整行失效
        val dim = if (shortcut.enabled) 1f else DISABLED_ROW_ALPHA
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            insideMargin = PaddingValues(0.dp)
        ) {
        BasicComponent(
            title = shortcut.label.ifEmpty { stringResource(R.string.shortcut_unnamed) },
            titleColor = BasicComponentDefaults.titleColor(
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = dim)
            ),
            summary = buildShortcutSummary(
                shortcut,
                stringResource(R.string.shortcut_uri_unset),
                stringResource(R.string.shortcut_toolbox_desc)
            ),
            summaryColor = BasicComponentDefaults.summaryColor(
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = dim)
            ),
            startAction = when {
                shortcut.kind == ShortcutKind.GROUP -> {
                    {
                        Box(Modifier.alpha(dim)) {
                            GroupIconPreview(
                                group = shortcut,
                                members = shortcut.children.orEmpty(),
                                name = shortcut.label,
                                size = 32f
                            )
                        }
                    }
                }
                iconPkg != null -> {
                    {
                        Box(Modifier.alpha(dim)) {
                            SettingsAppIcon(packageName = iconPkg, appName = shortcut.label, size = 32f)
                        }
                    }
                }
                else -> null
            },
            endActions = {
                // 行内启停：即时写盘即时按分区规则落位；批量态置灰不可点（点击交给勾选）
                Switch(
                    checked = shortcut.enabled,
                    onCheckedChange = if (selecting) null else ({ v -> onToggle(shortcut, v) }),
                    enabled = !selecting
                )
                // 行尾 48dp 槽位**恒定存在**：批量=勾选圈、已启用=拖动手柄、未启用=空占位。
                // 两种模式行宽行高/行形态完全一致 ⇒ 长按进批量时列表项不发生位移
                //（旧实现在批量态整表换成 BasicComponent 平铺，行尾元素与分区标题一起消失，
                //  animateItem 把这些差异演成"项目跳到别处"；2026-10-25 用户走查）。
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        // 拖动手柄：48dp 触摸热区（Android 最小触摸目标建议值；图标视觉仍 24dp）；
                        // 松手一次落盘。suppressRowLongPress 先消费 down——手柄按下不得漏给行内长按。
                        // 批量态不挂 draggableHandle：此时点行尾=勾选，不该能拖序。
                        .then(
                            if (!selecting && showHandle) {
                                Modifier
                                    .suppressRowLongPress()
                                    .draggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = {
                                            ShortcutStore.saveUserShortcuts(prefs, workingList)
                                        }
                                    )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        selecting -> SelectionCircle(checked = selected)
                        showHandle -> Icon(
                            imageVector = MiuixIcons.Sort,
                            contentDescription = stringResource(R.string.shortcut_drag_handle),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(24.dp)
                        )
                        // 空占位：只为与上面两态等宽，无内容
                        else -> Unit
                    }
                }
            },
            modifier = Modifier
                .semantics { this.selected = selected }
                // 单击与长按必须同一节点裁决（combinedClickable）：
                // foundation 1.10 的 node clickable 先消费 DOWN，外挂
                // detectTapGestures 的 awaitFirstDown 只认未消费 down，
                // 长按检测永远收不到手势（批量模式失效根因）。
                // 批量态：单击=勾选，长按不响应（已经在批量里了）。
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelected(shortcut) else onEdit(shortcut) },
                    onLongClick = if (selecting) null else ({ onEnterSelection(shortcut) })
                )
        )
        }
    }
    // 统一包 ReorderableItem：content 需要其 scope 接收者（draggableHandle）；
    // 未启用行无手柄、注册进 reorder 状态无副作用
    ReorderableItem(
        state = reorderState,
        key = shortcut.id,
        animateItemModifier = Modifier.animateItem()
    ) { isDragging -> content(isDragging) }
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
        // 2026-11-03：列表只显示类短名。整串 pkg/class 在 1.25 倍字体下折三行，
        // 整页被撑散；完整组件名在编辑页可见。
        ShortcutKind.COMPONENT, ShortcutKind.ACTIVITY -> {
            val act = shortcut.activityName ?: "?"
            act.substringAfterLast('.').ifEmpty { act }
        }
        ShortcutKind.INTENT_URI -> shortcut.intentUri ?: unsetUri
        ShortcutKind.TOOLBOX -> toolboxLabel
        ShortcutKind.SERVICE -> {
            val svc = shortcut.serviceName ?: "?"
            svc.substringAfterLast('.').ifEmpty { svc }
        }
        ShortcutKind.QS_TILE -> {
            val svc = shortcut.serviceName ?: "?"
            svc.substringAfterLast('.').ifEmpty { svc }
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
    // 2026-11-03：上限 40 → 26。40 字在 1.25 倍字体下会折三行，整页被撑散
    return if (raw.length > 26) raw.take(24) + "…" else raw
}
