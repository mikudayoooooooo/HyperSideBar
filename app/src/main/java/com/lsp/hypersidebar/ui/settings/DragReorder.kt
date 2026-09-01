package com.lsp.hypersidebar.ui.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * LazyColumn 拖动排序（§2.4 用户拍板：快捷方式与固定应用列表均用拖动手柄排序）。
 *
 * 模型：手柄长按起拖；拖动中越过相邻项即交换（onMove 只改内存列表，靠 key 寻址
 * ——layoutInfo 的 index 含分组标题等非数据项，不可直接当数据下标）；松手
 * onDragFinished 一次落盘。交换瞬间做位移补偿，被拖项视觉跟手；其余项交给
 * animateItemPlacement 平滑让位。
 */
class DragReorderState internal constructor(
    val listState: LazyListState,
    private val onMoveByKey: (fromKey: Any, toKey: Any) -> Unit,
    private val onDragFinished: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var dragOffset by mutableFloatStateOf(0f)
        internal set

    private fun infoOf(key: Any) =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    internal fun start(key: Any) {
        draggingKey = key
        dragOffset = 0f
    }

    internal fun drag(delta: Offset) {
        dragOffset += delta.y
        val key = draggingKey ?: return
        val info = infoOf(key) ?: return
        val center = info.offset + dragOffset + info.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key && center >= item.offset && center < item.offset + item.size
        } ?: return
        onMoveByKey(key, target.key)
        // 交换后本项占据 target 槽位；补偿偏移使视觉位置不跳
        dragOffset += info.offset - target.offset
    }

    internal fun end(cancelled: Boolean) {
        if (draggingKey != null && !cancelled) onDragFinished()
        draggingKey = null
        dragOffset = 0f
    }
}

@Composable
internal fun rememberDragReorderState(
    listState: LazyListState,
    onMoveByKey: (fromKey: Any, toKey: Any) -> Unit,
    onDragFinished: () -> Unit,
): DragReorderState {
    val latestMove by rememberUpdatedState(onMoveByKey)
    val latestFinish by rememberUpdatedState(onDragFinished)
    return remember(listState) {
        DragReorderState(
            listState = listState,
            onMoveByKey = { f, t -> latestMove(f, t) },
            onDragFinished = { latestFinish() }
        )
    }
}

/** 拖动手柄：长按起拖。加在列表行尾的手柄图标上。 */
internal fun Modifier.dragReorderHandle(state: DragReorderState, key: Any): Modifier =
    pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.drag(dragAmount)
            },
            onDragEnd = { state.end(cancelled = false) },
            onDragCancel = { state.end(cancelled = true) }
        )
    }

/** 列表行视觉态：被拖项浮起跟手（zIndex + translationY），其余项静止。 */
internal fun Modifier.dragReorderItem(state: DragReorderState, key: Any): Modifier =
    zIndex(if (key == state.draggingKey) 1f else 0f)
        .graphicsLayer {
            translationY = if (key == state.draggingKey) state.dragOffset else 0f
        }
