package com.lsp.hypersidebar.ui.settings

import android.content.SharedPreferences
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val TAG = "GroupEditPage"

/**
 * 快捷组编辑页（C8，计划 §B2）：组名 + 成员管理（勾选现有单条目入组 / 拖动调序 /
 * 移出组）+ 解散。所有校验走 [ShortcutGroups] 纯核心，UI 只渲染结果。
 *
 * 存储语义（§B1）：成员嵌入组条目（children），不再出现在顶层列表；移出/解散 =
 * 成员回到顶层。配额=组+成员各占一位（storageUnits ≤ MAX_STORED_SHORTCUTS）。
 */
@Composable
internal fun GroupEditPage(
    prefs: SharedPreferences,
    group: ShortcutAction,
    isNew: Boolean,
    /** 刷新令牌（同 ShortcutListPage）：列表页与组页互跳后重读顶层列表 */
    revision: Int = 0,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 顶层列表（成员来源/回填目标）；revision 变化（列表页保存返回）重建即重读
    var topList by remember(group.id, revision) { mutableStateOf(ShortcutStore.loadUserShortcuts(prefs)) }
    var name by remember(group.id) { mutableStateOf(group.label) }
    // 组成员（顺序即展示顺序；新组随勾选追加，编辑组从 children 起）
    var members by remember(group.id) { mutableStateOf(group.children.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    val memberIds = members.map { it.id }.toSet()
    // 可入组候选：顶层单条目（非组非工具箱）
    val candidates = topList.filter {
        it.kind != ShortcutKind.GROUP && it.kind != ShortcutKind.TOOLBOX && it.id !in memberIds
    }

    // 组合外字符串（save 闭包用）
    val invalidMsg = stringResource(R.string.group_edit_invalid)
    val quotaExceededMsg = stringResource(R.string.group_edit_quota_exceeded)
    val defaultName = stringResource(R.string.group_edit_title)

    fun buildTopList(updatedGroup: ShortcutAction?, removedToTop: List<ShortcutAction>): List<ShortcutAction> {
        val current = ShortcutStore.loadUserShortcuts(prefs)
        return if (isNew || updatedGroup == null) {
            // 新建：吸收的成员从顶层摘除，组插到首位成员原位置（找不到则末尾）
            val absorbed = members.map { it.id }.toSet()
            val withoutAbsorbed = current.filter { it.id !in absorbed }
            if (updatedGroup == null) {
                // 解散（新建态=直接放弃）：无组条目
                withoutAbsorbed
            } else {
                val firstIdx = current.indexOfFirst { it.id in absorbed }
                val insertAt = if (firstIdx >= 0) firstIdx.coerceAtMost(withoutAbsorbed.size) else withoutAbsorbed.size
                withoutAbsorbed.toMutableList().apply { add(insertAt, updatedGroup) }
            }
        } else {
            // 编辑：替换组条目（原位置），被移出的成员紧跟组后回顶层
            val idx = current.indexOfFirst { it.id == group.id }
            val withoutGroup = current.filter { it.id != group.id }
            val insertAt = if (idx >= 0) idx.coerceAtMost(withoutGroup.size) else withoutGroup.size
            withoutGroup.toMutableList().apply {
                addAll(insertAt, listOf(updatedGroup) + removedToTop)
            }
        }
    }

    fun save(dissolve: Boolean) {
        if (dissolve && isNew) { onBack(); return }
        if (dissolve) {
            val removed = group.children.orEmpty()
            val newList = buildTopList(null, removed)
            ShortcutStore.saveUserShortcuts(prefs, newList)
            HLog.i(TAG, "group dissolved: ${group.label} members back=${removed.size}")
            onBack(); return
        }
        if (!ShortcutGroups.validateChildren(members)) {
            error = invalidMsg; return
        }
        val updated = group.copy(
            kind = ShortcutKind.GROUP,
            label = name.ifBlank { defaultName },
            children = members
        )
        val removed = group.children.orEmpty().filter { c -> members.none { it.id == c.id } }
        val newList = buildTopList(updated, removed)
        if (ShortcutGroups.storageUnits(newList) > ShortcutStore.MAX_STORED_SHORTCUTS) {
            error = quotaExceededMsg; return
        }
        ShortcutStore.saveUserShortcuts(prefs, newList)
        HLog.i(TAG, "group saved: ${updated.label} members=${members.size}")
        onBack()
    }

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
    val haptics = LocalHapticFeedback.current

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
            item {
                SmallTitle(text = stringResource(R.string.group_edit_name))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            label = stringResource(R.string.group_edit_name)
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
                    BasicComponent(
                        title = member.label.ifBlank { member.id },
                        summary = member.kind.name,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale; scaleY = scale
                        },
                        endActions = {
                            TextButton(
                                text = stringResource(R.string.group_edit_remove),
                                onClick = {
                                    members = members.filter { it.id != member.id }
                                    error = null
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Box(
                                modifier = Modifier
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
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    )
                }
            }
            if (candidates.isNotEmpty() && members.size < ShortcutGroups.MAX_CHILDREN) {
                item {
                    SmallTitle(text = stringResource(R.string.group_edit_add_members))
                }
                itemsIndexed(candidates, key = { _, c -> "cand_${c.id}" }) { _, cand ->
                    BasicComponent(
                        title = cand.label.ifBlank { cand.id },
                        summary = cand.kind.name,
                        onClick = {
                            members = members + cand
                            error = null
                        }
                    )
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
        // 收底动作：保存 + 解散（HyperOS 批量范式收底位）
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                TextButton(
                    text = stringResource(R.string.group_edit_save),
                    onClick = { save(dissolve = false) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isNew) {
                    TextButton(
                        text = stringResource(R.string.group_edit_dissolve),
                        onClick = { save(dissolve = true) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
