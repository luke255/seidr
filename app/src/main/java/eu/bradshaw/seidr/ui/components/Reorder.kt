package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.ui.isSelect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Minimal drag-to-reorder for a LazyColumn, driven by a drag handle.
 * While dragging, [onMove] is called with list keys each time the dragged row
 * crosses a neighbour, so the caller can reorder its local list. [canMoveTo]
 * keeps a row inside its own section. [onDragEnd] commits the final order.
 * With a remote or keyboard, select on the handle lifts the row, up/down move
 * it one place at a time and select (or moving focus away) drops it.
 */
class ReorderState(val listState: LazyListState) {
    internal var onMove: (fromKey: Any, toKey: Any) -> Unit = { _, _ -> }
    internal var canMoveTo: (fromKey: Any, toKey: Any) -> Boolean = { _, _ -> true }
    internal var onDragEnd: () -> Unit = {}

    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(key: Any) { draggingKey = key; offset = 0f }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        offset += dy
        val items = listState.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == key } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = items.firstOrNull { it.key != key && center.toInt() in it.offset..(it.offset + it.size) } ?: return
        if (!canMoveTo(key, target.key)) return
        // Keep the row under the finger after the list re-lays out.
        offset += current.offset - target.offset
        onMove(key, target.key)
    }

    /** Moves the lifted row [key] one place up (-1) or down (+1). */
    fun step(key: Any, dir: Int) {
        val info = listState.layoutInfo
        val items = info.visibleItemsInfo
        val i = items.indexOfFirst { it.key == key }
        val target = items.getOrNull(i + dir)?.takeIf { i >= 0 } ?: return
        if (!canMoveTo(key, target.key)) return
        onMove(key, target.key)
        // The list doesn't follow a focused row that moves, so scroll a row's
        // worth once it nears an edge.
        val first = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        if (dir > 0 && target.offset + 2 * target.size > info.viewportEndOffset) listState.requestScrollToItem(first + 1, offset)
        if (dir < 0 && target.offset - target.size < info.viewportStartOffset) listState.requestScrollToItem((first - 1).coerceAtLeast(0), offset)
    }

    fun end() {
        if (draggingKey != null) onDragEnd()
        draggingKey = null
        offset = 0f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (Any, Any) -> Unit,
    canMoveTo: (Any, Any) -> Boolean = { _, _ -> true },
    onDragEnd: () -> Unit,
): ReorderState = remember(listState) { ReorderState(listState) }.also {
    // Callbacks capture fresh state each composition.
    it.onMove = onMove
    it.canMoveTo = canMoveTo
    it.onDragEnd = onDragEnd
}

/** Put on the drag handle of the row with [key]. */
@Composable
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this
        .pointerInput(key) {
            detectDragGestures(
                onDragStart = { state.start(key) },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
                onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            )
        }
        .onFocusChanged { if (!it.isFocused && state.draggingKey == key) state.end() }
        .onKeyEvent { e ->
            val lifted = state.draggingKey == key
            when {
                e.isSelect -> {
                    if (e.type == KeyEventType.KeyUp) { if (lifted) state.end() else state.start(key) }
                    true
                }
                lifted && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                    if (e.type == KeyEventType.KeyDown) state.step(key, if (e.key == Key.DirectionUp) -1 else 1)
                    true
                }
                else -> false
            }
        }
        .focusable(interactionSource = interaction)
        .indication(interaction, ripple(bounded = false, radius = 20.dp))
}

/** Put on the row itself so it follows the finger while dragged. */
fun Modifier.reorderable(state: ReorderState, key: Any): Modifier =
    if (state.draggingKey == key) zIndex(1f).graphicsLayer { translationY = state.offset; shadowElevation = 8f }
    else this

/**
 * The drag handle icon. While [lifted] by a remote it turns into a highlighted
 * up/down arrow, so it's clear the arrow keys now move the row.
 */
@Composable
fun LiftHandle(lifted: Boolean, modifier: Modifier) {
    Icon(
        if (lifted) Icons.Filled.SwapVert else Icons.Filled.DragHandle,
        if (lifted) "Moving: up or down, then select to drop" else "Drag to reorder",
        modifier.padding(8.dp),
        tint = if (lifted) MaterialTheme.colorScheme.primary else LocalContentColor.current,
    )
}
