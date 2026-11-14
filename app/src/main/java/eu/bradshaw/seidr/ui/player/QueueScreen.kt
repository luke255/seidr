package eu.bradshaw.seidr.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import eu.bradshaw.seidr.ui.LocalPrimaryFocus
import eu.bradshaw.seidr.ui.keys
import eu.bradshaw.seidr.ui.primaryFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.LiftHandle
import eu.bradshaw.seidr.ui.components.MenuAction
import eu.bradshaw.seidr.ui.components.TrackMenu
import eu.bradshaw.seidr.ui.components.dragHandle
import eu.bradshaw.seidr.ui.components.rememberReorderState
import eu.bradshaw.seidr.ui.components.reorderable

/**
 * Shuffle and repeat sit in the header. Below: "Earlier" (what came before the current track, dimmed), "Now playing", then "Next in queue" (tracks the user added, which play
 * first), then "Next from: <context>". Drag the handle to reorder within a
 * section; swipe a row away to remove it. The screen opens with the last
 * earlier track peeking above "Now playing"; scroll up for the rest. Long-press a row for its menu,
 * which can also remove it (the way to do that with a remote).
 */
@Composable
fun QueueScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val player = actions.player
    val ui by player.ui.collectAsStateWithLifecycle()
    // Items before "Now playing" are the "Earlier" header and one row per earlier
    // track; open on the last of those rows so what just played shows above it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ui.index.coerceAtLeast(0))

    // Local mirror so drags feel immediate; the player gets one move on release.
    val local = remember(ui.queue, ui.index) { mutableStateListOf<QueueEntry>().apply { addAll(ui.queue) } }
    var dragFrom by remember { mutableStateOf(-1) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    val userKeys = remember(ui.queue, ui.index) { ui.userRange.map { ui.queue[it].key }.toSet() }
    // Earlier tracks can't be dragged, nor dragged into.
    val upcomingKeys = remember(ui.queue, ui.index) { ui.queue.drop(ui.index + 1).map { it.key }.toSet() }

    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            val f = local.indexOfFirst { it.key == from }
            val t = local.indexOfFirst { it.key == to }
            if (f >= 0 && t >= 0) {
                if (dragKey != from) { dragKey = from as String; dragFrom = ui.queue.indexOfFirst { it.key == from } }
                local.add(t, local.removeAt(f))
            }
        },
        canMoveTo = { from, to -> to is String && to in upcomingKeys && ((from in userKeys) == (to in userKeys)) },
        onDragEnd = {
            val key = dragKey
            if (key != null && dragFrom >= 0) {
                val to = local.indexOfFirst { it.key == key }
                if (to >= 0 && to != dragFrom) player.moveQueueItem(dragFrom, to)
            }
            dragKey = null; dragFrom = -1
        },
    )

    // Picking a track makes it the "Now playing" row, a different list item, so the
    // focused row vanishes. Follow it there rather than leaving focus nowhere.
    var hasFocus by remember { mutableStateOf(false) }
    val nowRow = LocalPrimaryFocus.current
    val input = androidx.compose.ui.platform.LocalInputModeManager.current
    LaunchedEffect(ui.current?.key) {
        kotlinx.coroutines.delay(100)
        if (!hasFocus && input.keys) nowRow?.let { runCatching { it.requestFocus() } }
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().onFocusChanged { hasFocus = it.hasFocus }) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
            Text("Play Queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ShuffleButton(ui.shuffle, onToggle = player::toggleShuffle, onReshuffle = player::reshuffle)
            RepeatButton(ui.repeat, onClick = player::cycleRepeat)
        }
        val cur = ui.current
        if (cur == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("The queue is empty") }
            return@Column
        }
        val earlier = local.take(ui.index)
        val user = local.filter { it.key in userKeys }
        val after = local.drop(ui.index + 1).filter { it.key !in userKeys }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (earlier.isNotEmpty()) {
                item(key = "h:earlier") { Header("Earlier") }
                items(earlier, key = { it.key }) { e ->
                    val remove: () -> Unit = { player.removeQueueItem(ui.queue.indexOfFirst { it.key == e.key }) }
                    SwipeRemove(onRemove = remove) {
                        QueueRow(e, playing = false, past = true,
                            onClick = { player.skipTo(ui.queue.indexOfFirst { it.key == e.key }) },
                            onRemove = remove, handle = null)
                    }
                }
            }
            item(key = "h:now") { Header("Now playing") }
            item(key = "now") { QueueRow(cur, playing = true, onClick = {}, onRemove = null, handle = null, modifier = Modifier.primaryFocus()) }
            if (user.isNotEmpty()) {
                item(key = "h:user") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Header("Next in queue") }
                        TextButton(onClick = player::clearUserQueue, modifier = Modifier.padding(end = 8.dp)) { Text("Clear queue") }
                    }
                }
                items(user, key = { it.key }) { e ->
                    val remove: () -> Unit = { player.removeQueueItem(ui.queue.indexOfFirst { it.key == e.key }) }
                    SwipeRemove(onRemove = remove) {
                        QueueRow(e, playing = false, modifier = Modifier.reorderable(reorder, e.key),
                            onClick = { player.skipTo(ui.queue.indexOfFirst { it.key == e.key }) },
                            onRemove = remove, lifted = reorder.draggingKey == e.key,
                            handle = Modifier.dragHandle(reorder, e.key))
                    }
                }
            }
            if (after.isNotEmpty()) {
                item(key = "h:ctx") { Header("Next from: ${ui.contextName ?: "queue"}") }
                items(after, key = { it.key }) { e ->
                    val remove: () -> Unit = { player.removeQueueItem(ui.queue.indexOfFirst { it.key == e.key }) }
                    SwipeRemove(onRemove = remove) {
                        QueueRow(e, playing = false, modifier = Modifier.reorderable(reorder, e.key),
                            onClick = { player.skipTo(ui.queue.indexOfFirst { it.key == e.key }) },
                            onRemove = remove, lifted = reorder.draggingKey == e.key,
                            handle = Modifier.dragHandle(reorder, e.key))
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun SwipeRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) onRemove()
    }
    SwipeToDismissBox(state, backgroundContent = {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }) { content() }
}

@Composable
private fun QueueRow(
    e: QueueEntry, playing: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?, handle: Modifier?,
    modifier: Modifier = Modifier, lifted: Boolean = false, past: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    // The handle sits beside the clickable part rather than inside it, so a remote can reach it.
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        Modifier.weight(1f)
            .combinedClickable(onLongClickLabel = "More", onLongClick = { menu = true }, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .alpha(if (past) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Cover(e.coverArt, Modifier.size(44.dp), size = 120)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(e.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
        if (handle != null) LiftHandle(lifted, handle)
        Box {
            TrackMenu(
                e.asSong(), expanded = menu, onDismiss = { menu = false },
                extra = listOfNotNull(onRemove?.let { MenuAction("Remove from queue", Icons.Filled.RemoveCircleOutline, onClick = it) }),
            )
        }
    }
}
