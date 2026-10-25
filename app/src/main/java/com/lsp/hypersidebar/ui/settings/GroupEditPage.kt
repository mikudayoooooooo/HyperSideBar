package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.lsp.hypersidebar.R
import com.lsp.hypersidebar.util.ShortcutAction
import com.lsp.hypersidebar.util.ShortcutGroups
import com.lsp.hypersidebar.util.ShortcutKind
import com.lsp.hypersidebar.util.ShortcutStore
import com.lsp.hypersidebar.util.HLog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog

private const val TAG = "GroupEditPage"

/**
 * 组详情顶栏桥（同 ShortcutSelectionBar 模式）：右上角＋=添加现有成员（打开
 * 页内成员选择器）；顶栏归 MainScreen 所有，经此共享。
 */
internal class GroupEditBar {
    var onAddMember: (() -> Unit)? = null
}

/**
 * 组详情（布局定稿 §5，2026-10-25）：拼贴预览 + 组名 + 组开关 + 成员管理
 * （开关/拖动调序=展开顺序/单击编辑/长按多选移出删除）＋成员选择器（WindowDialog，
 * 0912 弹层选型定案）+ 保存/解散。成员编辑跳转前若有未保存改动先静默保存
 * （新建组首次落盘后 created 置位走编辑分支），校验失败留在本页提示（§5.1）。
 * 成员编辑返回经 revision 重读**盘上**组的 children（NavKey 携带的是压栈时快照）。
 */
@Composable
internal fun GroupEditPage(
    prefs: SharedPreferences,
    group: ShortcutAction,
    isNew: Boolean,
    /** 刷新令牌（成员经编辑页保存返回后重读盘上组数据；未落盘的新建组不重读） */
    revision: Int = 0,
    bar: GroupEditBar? = null,
    onEditMember: (ShortcutAction) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 新建组首次落盘后置位（之后所有读写走"已存在"分支，防重复建组）
    var created by remember(group.id) { mutableStateOf(false) }
    val onDisk = !isNew || created
    var topList by remember(group.id, revision) {
        mutableStateOf(ShortcutStore.loadUserShortcuts(prefs))
    }
    var name by remember(group.id) { mutableStateOf(group.label) }
    var groupEnabled by remember(group.id) { mutableStateOf(group.enabled) }
    // 成员：未落盘=NavKey 携带（批量建组预填）；已落盘=盘上组的 children（成员编辑返回刷新）
    var members by remember(group.id, if (onDisk) revision else 0) {
        mutableStateOf(
            if (onDisk) ShortcutStore.loadUserShortcuts(prefs)
                .firstOrNull { it.id == group.id }?.children.orEmpty()
            else group.children.orEmpty()
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var memberSelection by remember { mutableStateOf(false) }
    var selectedMemberIds by remember { mutableStateOf(setOf<String>()) }
    var showPicker by remember { mutableStateOf(false) }
    var showDissolveConfirm by remember { mutableStateOf(false) }
    var pendingMemberOp by remember { mutableStateOf<String?>(null) }   // "remove" | "delete"
    var showUnderflowConfirm by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current
    val invalidMsg = stringResource(R.string.group_edit_invalid)
    val quotaExceededMsg = stringResource(R.string.group_edit_quota_exceeded)
    val defaultName = stringResource(R.string.group_edit_title)

    fun dirty(): Boolean =
        name != group.label || groupEnabled != group.enabled || members != group.children.orEmpty()

    fun diskChildren(): List<ShortcutAction> =
        ShortcutStore.loadUserShortcuts(prefs).firstOrNull { it.id == group.id }?.children.orEmpty()

    fun buildTopList(updatedGroup: ShortcutAction, removedToTop: List<ShortcutAction>): List<ShortcutAction> {
        val current = ShortcutStore.loadUserShortcuts(prefs)
        return if (!onDisk) {
            val absorbed = members.map { it.id }.toSet()
            val withoutAbsorbed = current.filter { it.id !in absorbed }
            val firstIdx = current.indexOfFirst { it.id in absorbed }
            val insertAt = if (firstIdx >= 0) firstIdx.coerceAtMost(withoutAbsorbed.size) else withoutAbsorbed.size
            withoutAbsorbed.toMutableList().apply { add(insertAt, updatedGroup) }
        } else {
            val idx = current.indexOfFirst { it.id == group.id }
            val withoutGroup = current.filter { it.id != group.id }
            val insertAt = if (idx >= 0) idx.coerceAtMost(withoutGroup.size) else withoutGroup.size
            withoutGroup.toMutableList().apply { addAll(insertAt, listOf(updatedGroup) + removedToTop) }
        }
    }

    fun persist(updated: ShortcutAction) {
        val removed = diskChildren().filter { c -> members.none { it.id == c.id } }
        ShortcutStore.saveUserShortcuts(prefs, buildTopList(updated, removed))
        if (!onDisk) created = true
        HLog.i(TAG, "group saved: ${updated.label} members=${members.size}")
    }

    fun validatedGroup(): ShortcutAction? {
        if (!ShortcutGroups.validateChildren(members)) {
            error = invalidMsg
            return null
        }
        val updated = group.copy(
            kind = ShortcutKind.GROUP,
            label = name.ifBlank { defaultName },
            enabled = groupEnabled,
            children = members
        )
        val removed = diskChildren().filter { c -> members.none { it.id == c.id } }
        if (ShortcutGroups.storageUnits(buildTopList(updated, removed)) > ShortcutStore.MAX_STORED_SHORTCUTS) {
            error = quotaExceededMsg
            return null
        }
        error = null
        return updated
    }

    /** 解散：删组容器，returned 成员回到组原位置（按钮=当前全部成员；不足确认按操作语义取子集） */
    fun dissolveWith(returned: List<ShortcutAction>) {
        if (!onDisk) { onBack(); return }
        val current = ShortcutStore.loadUserShortcuts(prefs)
        val idx = current.indexOfFirst { it.id == group.id }
        val without = current.filter { it.id != group.id }
        val insertAt = if (idx >= 0) idx.coerceAtMost(without.size) else without.size
        ShortcutStore.saveUserShortcuts(prefs, without.toMutableList().apply { addAll(insertAt, returned) })
        HLog.i(TAG, "group dissolved: ${group.label} members back=${returned.size}")
        onBack()
    }

    fun save() {
        val updated = validatedGroup() ?: return
        persist(updated)
        onBack()
    }

    /** 成员编辑跳转（§5.1）：dirty 先静默保存；校验失败留在本页提示（不静默丢草稿） */
    fun jumpToMember(member: ShortcutAction) {
        if (dirty()) {
            val updated = validatedGroup() ?: return
            persist(updated)
        }
        onEditMember(member)
    }

    /**
     * 成员多选操作（§5.4）。致成员 <2 时先走"解散组并继续"确认（不静默留非法组）；
     * confirmed=true 为确认后的直通执行。
     */
    fun applyMemberOp(op: String, confirmed: Boolean = false) {
        val remaining = members.filter { it.id !in selectedMemberIds }
        if (!confirmed && remaining.size < ShortcutGroups.MIN_CHILDREN) {
            pendingMemberOp = op
            showUnderflowConfirm = true
            return
        }
        if (confirmed) {
            // 解散并继续：删除=剩余成员回顶层；移出=全部当前成员回顶层（含被选项）
            dissolveWith(if (op == "delete") remaining else members)
            return
        }
        members = remaining
        memberSelection = false
        selectedMemberIds = emptySet()
    }

    fun exitMemberSelection() {
        memberSelection = false
        selectedMemberIds = emptySet()
    }

    // 顶栏＋桥（添加成员 → 页内选择器）
    SideEffect { bar?.onAddMember = { showPicker = true } }
    DisposableEffect(Unit) { onDispose { bar?.onAddMember = null } }

    val memberIds = members.map { it.id }.toSet()
    val candidates = topList.filter {
        it.kind != ShortcutKind.GROUP && it.kind != ShortcutKind.TOOLBOX && it.id !in memberIds
    }
    val allMembersOff = members.isNotEmpty() && members.all { !it.enabled }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        scrollThresholdPadding = WindowInsets.systemBars.asPaddingValues(),
        scrollThreshold = 120.dp
    ) { from, to ->
        val fromIdx = members.indexOfFirst { it.id == from.key }
        val toIdx = members.indexOfFirst { it.id == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            members = members.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        }
    }

    val usedUnits = ShortcutGroups.storageUnits(topList)

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .overScrollVertical(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { SmallTitle(text = stringResource(R.string.group_edit_name)) }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 组预览：借用图标优先（与扇形端一致），否则三角拼贴
                        GroupPreview(group = group, members = members, name = name)
                        Spacer(Modifier.size(12.dp))
                        TextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            label = stringResource(R.string.group_edit_name)
                        )
                        BasicComponent(
                            title = stringResource(R.string.group_edit_group_switch),
                            summary = "",
                            endActions = {
                                Switch(checked = groupEnabled, onCheckedChange = { groupEnabled = it })
                            }
                        )
                    }
                }
            }
            item {
                SmallTitle(
                    text = stringResource(R.string.group_edit_members) +
                        "（${members.size}/${ShortcutGroups.MAX_CHILDREN}）"
                )
            }
            if (allMembersOff) {
                item {
                    Text(
                        text = stringResource(R.string.group_last_enabled_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            if (members.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.group_edit_add_members),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            itemsIndexed(members, key = { _, m -> m.id }) { _, member ->
                ReorderableItem(
                    state = reorderState,
                    key = member.id,
                    animateItemModifier = Modifier
                ) { isDragging ->
                    val scale by animateFloatAsState(if (isDragging) 1.03f else 1f)
                    if (memberSelection) {
                        val checked = member.id in selectedMemberIds
                        BasicComponent(
                            title = member.label.ifBlank { member.id },
                            summary = member.kind.name,
                            endActions = { SelectionCircle(checked = checked) },
                            onClick = {
                                selectedMemberIds = if (checked) selectedMemberIds - member.id
                                else selectedMemberIds + member.id
                            }
                        )
                    } else {
                        val memberIconPkg = member.iconPackageName ?: member.packageName
                        BasicComponent(
                            title = member.label.ifBlank { member.id },
                            summary = member.kind.name,
                            startAction = if (memberIconPkg != null) {
                                { SettingsAppIcon(packageName = memberIconPkg, appName = member.label, size = 32f) }
                            } else null,
                            endActions = {
                                // 成员开关：控制展开后是否出现（§5.3，不动根栏容量）
                                Switch(
                                    checked = member.enabled,
                                    onCheckedChange = { v ->
                                        members = members.map { if (it.id == member.id) it.copy(enabled = v) else it }
                                        error = null
                                    }
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(40.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                            },
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                }
                                .combinedClickable(
                                    onClick = { jumpToMember(member) },
                                    onLongClick = {
                                        memberSelection = true
                                        selectedMemberIds = setOf(member.id)
                                    }
                                )
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(
                        R.string.group_edit_quota, usedUnits, ShortcutStore.MAX_STORED_SHORTCUTS
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            error?.let { err ->
                item {
                    Text(
                        text = err,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (memberSelection) {
            // 成员多选收底（§5.4）：移出组 | 删除——两个动作不共用入口
            androidx.activity.compose.BackHandler { exitMemberSelection() }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.sinkClickable { applyMemberOp("remove") }
                ) {
                    Icon(MiuixIcons.Close, contentDescription = null, modifier = Modifier.size(26.dp))
                    Text(stringResource(R.string.group_member_remove))
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.sinkClickable { applyMemberOp("delete") }
                ) {
                    Icon(MiuixIcons.Delete, contentDescription = null, modifier = Modifier.size(26.dp))
                    Text(stringResource(R.string.group_member_delete))
                }
            }
        } else {
            // 收底动作：保存 + 解散
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextButton(
                        text = stringResource(R.string.group_edit_save),
                        onClick = { save() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!isNew) {
                        TextButton(
                            text = stringResource(R.string.group_edit_dissolve),
                            onClick = { showDissolveConfirm = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // ===== 成员选择器（WindowDialog，0912 弹层选型：少量勾选+模态确认）=====
    if (showPicker) {
        var pickerSel by remember { mutableStateOf(setOf<String>()) }
        val room = (ShortcutGroups.MAX_CHILDREN - members.size).coerceAtLeast(0)
        WindowDialog(
            show = showPicker,
            title = stringResource(R.string.group_member_picker_title),
            onDismissRequest = { showPicker = false },
            content = {
                Column {
                    candidates.take(12).forEach { cand ->
                        val checked = cand.id in pickerSel
                        BasicComponent(
                            title = cand.label.ifBlank { cand.id },
                            summary = cand.kind.name,
                            endActions = { SelectionCircle(checked = checked) },
                            onClick = {
                                pickerSel = if (checked) pickerSel - cand.id
                                else if (pickerSel.size < room) pickerSel + cand.id
                                else pickerSel
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.layout_sheet_cancel),
                            onClick = { showPicker = false }
                        )
                        TextButton(
                            text = stringResource(R.string.group_member_picker_confirm, pickerSel.size),
                            onClick = {
                                members = members + candidates.filter { it.id in pickerSel }
                                showPicker = false
                                error = null
                            },
                            colors = if (pickerSel.isEmpty()) ButtonDefaults.textButtonColors(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            ) else ButtonDefaults.textButtonColors()
                        )
                    }
                }
            }
        )
    }

    // ===== 解散确认 =====
    if (showDissolveConfirm) {
        WindowDialog(
            show = showDissolveConfirm,
            title = stringResource(R.string.group_dissolve_confirm_title),
            summary = stringResource(R.string.group_dissolve_confirm),
            onDismissRequest = { showDissolveConfirm = false },
            content = {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(R.string.layout_sheet_cancel),
                        onClick = { showDissolveConfirm = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.group_edit_dissolve),
                        onClick = {
                            showDissolveConfirm = false
                            dissolveWith(members)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.error)
                    )
                }
            }
        )
    }

    // ===== 成员不足确认（§5.4：取消 / 解散组并继续）=====
    if (showUnderflowConfirm) {
        WindowDialog(
            show = showUnderflowConfirm,
            title = stringResource(R.string.group_underflow_title),
            summary = stringResource(R.string.group_underflow_confirm),
            onDismissRequest = {
                showUnderflowConfirm = false
                pendingMemberOp = null
            },
            content = {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(R.string.layout_sheet_cancel),
                        onClick = {
                            showUnderflowConfirm = false
                            pendingMemberOp = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.group_underfall_continue),
                        onClick = {
                            showUnderflowConfirm = false
                            val op = pendingMemberOp
                            pendingMemberOp = null
                            if (op != null) applyMemberOp(op, confirmed = true)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.error)
                    )
                }
            }
        )
    }
}

/** 组预览：借用图标优先（与扇形端一致），否则上1下2三角拼贴（仅启用成员，与展开一致） */
@Composable
private fun GroupPreview(group: ShortcutAction, members: List<ShortcutAction>, name: String) {
    val borrowed = group.iconPackageName
    if (borrowed != null) {
        SettingsAppIcon(packageName = borrowed, appName = name, size = 56f)
        return
    }
    val cell = 56f
    val child = cell * 0.46f
    val gap = cell * 0.04f
    val enabledMembers = members.filter { it.enabled }.take(3)
    Box(modifier = Modifier.size(cell.dp)) {
        enabledMembers.forEachIndexed { i, m ->
            val mod = when (i) {
                0 -> Modifier.offset(x = ((cell - child) / 2f).dp, y = gap.dp)
                1 -> Modifier.offset(x = gap.dp, y = (cell - child - gap).dp)
                else -> Modifier.offset(x = (cell - child - gap).dp, y = (cell - child - gap).dp)
            }
            Box(modifier = mod) {
                SettingsAppIcon(
                    packageName = m.iconPackageName ?: m.packageName ?: "",
                    appName = m.label,
                    size = child
                )
            }
        }
    }
}
