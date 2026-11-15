package eu.bradshaw.seidr.ui.player

import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import eu.bradshaw.seidr.data.Lyrics
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.ui.LocalActions
import kotlinx.coroutines.delay

/** What Now Playing knows about the current song's lyrics. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Failed : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

/** Looks up [cur]'s lyrics whenever the song changes. */
@Composable
fun rememberLyrics(cur: QueueEntry): LyricsState {
    val library = LocalActions.current.graph.library
    var state by remember(cur.songId) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(cur.songId) {
        state = runCatching { library.lyrics(cur.songId, cur.artist, cur.title) }
            .fold({ it?.let(LyricsState::Ready) ?: LyricsState.None }, { LyricsState.Failed })
    }
    return state
}

/**
 * Lyrics in place of the artwork. Synced lyrics follow playback, keeping the
 * current line about a third of the way down; tap a line to jump to it. While
 * a finger is on them, and for a few seconds after, following pauses.
 * Instrumental stretches show a ♪ (see [Lyrics.withBreaks]) with a countdown
 * to the next line. While lyrics show and music plays, the screen stays on.
 * Not [interactive] (the visualiser's lyrics page), they only follow: no
 * scrolling, no line taps and no timing ruler (a saved nudge still applies).
 */
@Composable
fun LyricsPane(state: LyricsState, cur: QueueEntry, modifier: Modifier = Modifier, interactive: Boolean = true) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            LyricsState.Loading -> CircularProgressIndicator()
            LyricsState.None -> Message(stringResource(R.string.lyrics_none))
            LyricsState.Failed -> Message(stringResource(R.string.lyrics_failed))
            is LyricsState.Ready -> LyricsList(state.lyrics, cur, interactive)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LyricsList(raw: Lyrics, cur: QueueEntry, interactive: Boolean) {
    val actions = LocalActions.current
    val player = actions.player
    val offsets = actions.graph.lyricsOffsets
    val offsetsVersion by offsets.version.collectAsStateWithLifecycle()
    val ui by player.ui.collectAsStateWithLifecycle()
    val lyrics = remember(raw, cur.duration) { raw.withBreaks(cur.duration * 1000L) }

    // No dimming while reading along. Paused, the screen times out as usual. showPause
    // rather than isPlaying, so buffering between tracks doesn't let it dim.
    val view = LocalView.current
    DisposableEffect(view, ui.showPause) {
        view.keepScreenOn = ui.showPause
        onDispose { view.keepScreenOn = false }
    }
    // The user's timing nudge for this song (see TimingRuler). Re-read when a sync brings a new one.
    var adjust by remember(cur.songId, offsetsVersion) { mutableLongStateOf(offsets.get(cur.songId)) }
    // Open on the current line rather than at the top and then scrolling down to it.
    val initial = remember(lyrics) { if (lyrics.synced) lyrics.lineAt(player.positionMs + adjust) else -1 }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceAtLeast(0))
    var active by remember(lyrics) { mutableIntStateOf(initial) }
    // Seconds to the next line while an instrumental ♪ is the active line, else -1.
    var countdown by remember(lyrics) { mutableIntStateOf(-1) }

    // Following pauses from the moment a finger lands (before it has moved far enough to
    // count as a drag), through any fling, and for HOLD_MS after; [hand] holds the
    // finger-down flag and when the list was last the user's (uptime ms). A tap on a
    // line clears it to jump straight there.
    val hand = remember { longArrayOf(0L, 0L) }
    val scope = rememberCoroutineScope()
    // The sub-pixel part of the glide, applied as a layer translation (see fadeEdges).
    var shift by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Top padding puts a scrolled-to line at a third of the height; bottom lets the last line get there.
        val top = if (lyrics.synced) maxHeight / 3 else 16.dp
        val lineStyle = (if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
            .copy(fontWeight = FontWeight.Bold)
        val path = rememberLyricsPath(lyrics, lineStyle, constraints.maxWidth)
        if (path != null) {
            // Every frame while playing; polls while paused too, so a seek (line tap or scrubber)
            // moves the highlight and the list.
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val placed = remember(lyrics) { booleanArrayOf(false) } // the first placement snaps
            LaunchedEffect(path, cur.key, ui.isPlaying) {
                val playing = ui.isPlaying
                val clock = SongClock()
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        val song = if (playing) withFrameNanos { clock.at(it, player.positionMs) } else player.positionMs.toDouble()
                        val pos = song + adjust
                        active = lyrics.lineAt(pos.toLong())
                        countdown = countdownAt(lyrics, active, pos)
                        shift = 0f
                        val now = SystemClock.uptimeMillis()
                        // Scrolling with no finger down and nothing of ours running: a fling (or a wheel).
                        if (hand[0] == 1L || list.isScrollInProgress) hand[1] = now
                        if (now - hand[1] > HOLD_MS) {
                            try {
                                shift = list.follow(path, active, pos, snap = !placed[0])
                                placed[0] = true
                            } catch (e: CancellationException) {
                                // Outranked by the user's own scrolling; carry on next time.
                                if (!currentCoroutineContext().isActive) throw e
                            }
                        }
                        if (!playing) delay(250)
                    }
                }
            }
        }
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .fadeEdges { shift }
                .then(if (!interactive) Modifier else Modifier.pointerInput(Unit) {
                    // Watches without consuming, ahead of the list and the lines (Initial pass).
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.any { it.pressed }
                            if (down && hand[0] == 0L) {
                                // Stop a catch-up glide under the finger, so the touch lands on still lyrics.
                                scope.launch { list.stopScroll(MutatePriority.Default) }
                            }
                            hand[0] = if (down) 1L else 0L
                            hand[1] = SystemClock.uptimeMillis()
                        }
                    }
                }),
            userScrollEnabled = interactive,
            contentPadding = PaddingValues(top = top, bottom = if (lyrics.synced) maxHeight * 2 / 3 else 16.dp),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                if (!lyrics.synced && line.text.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    return@itemsIndexed
                }
                val colour by animateColorAsState(
                    when {
                        !lyrics.synced || i == active -> MaterialTheme.colorScheme.onBackground
                        i < active -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                    label = "line",
                )
                val start = line.startMs
                val left = if (i == active) countdown else -1
                Text(
                    if (line.text.isNotBlank()) buildAnnotatedString { append(line.text) }
                    else buildAnnotatedString {
                        append("♪")
                        // Smaller than the line, so the line is no taller than the measured "♪".
                        if (left >= 0) withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal,
                            fontSize = lineStyle.fontSize * 0.75f, color = colour.copy(alpha = colour.alpha * 0.7f))) {
                            append("  "); append(formatCountdown(left))
                        }
                    },
                    style = lineStyle,
                    color = colour,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (start != null && interactive) Modifier.clickable { player.seekTo((start - adjust).coerceAtLeast(0)); hand[1] = 0 } else Modifier)
                        .padding(vertical = LINE_PADDING),
                )
            }
        }
        if (lyrics.synced && interactive) {
            TimingRuler(
                adjust = adjust,
                onAdjust = { adjust = it; active = lyrics.lineAt(player.positionMs + it) },
                onDone = { offsets.set(cur.songId, adjust) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Getting back on track after a seek or the user's scrolling. */
private val catchUp = tween<Float>(500, easing = EaseInOutCubic)
/** The same from more than a screen away. */
private val longCatchUp = tween<Float>(750, easing = EaseInOutCubic)

/** How long following stays paused after the user's last touch or fling. */
private const val HOLD_MS = 3000L

/**
 * Whole seconds (rounded up) until the line after [active], when [active] is an
 * instrumental ♪; -1 otherwise, or when nothing follows.
 */
private fun countdownAt(lyrics: Lyrics, active: Int, posMs: Double): Int {
    val line = lyrics.lines.getOrNull(active) ?: return -1
    if (line.text.isNotBlank()) return -1
    val next = lyrics.lines.getOrNull(active + 1)?.startMs ?: return -1
    return ceil((next - posMs) / 1000).toInt().coerceAtLeast(0)
}

/** 07, 59, then 1:00, 12:05. */
internal fun formatCountdown(seconds: Int): String =
    if (seconds > 59) "%d:%02d".format(seconds / 60, seconds % 60) else "%02d".format(seconds)

private val LINE_PADDING = 6.dp

/** How far a speed change is spread (the blur's deviation). */
private const val SMOOTHING_MS = 2500.0

/** How far a line may sit from the mark when it starts. */
private val BAND = 48.dp

/**
 * The scroll path for synced [lyrics] (null otherwise). It needs every line's
 * height, not just the visible ones, so each is measured as the list will lay it
 * out: [style] at [width], plus the item's padding.
 */
@Composable
private fun rememberLyricsPath(lyrics: Lyrics, style: TextStyle, width: Int): LyricsPath? {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    return remember(lyrics, style, width, density) {
        if (!lyrics.synced || lyrics.lines.isEmpty()) return@remember null
        val padding = with(density) { LINE_PADDING.roundToPx() } * 2
        var y = 0f
        val tops = FloatArray(lyrics.lines.size) { i ->
            val top = y
            y += measurer.measure(lyrics.lines[i].text.ifBlank { "♪" }, style, constraints = Constraints(maxWidth = width)).size.height + padding
            top
        }
        LyricsPath(lyrics.lines.map { it.startMs ?: 0 }, tops, SMOOTHING_MS, with(density) { BAND.toPx() })
    }
}

/**
 * Keeps synced lyrics moving with the song along [path], anchored on line [i]
 * (the first line before the song reaches it). A jump (seek, the user's
 * scrolling) is caught up with [catchUp], or taken at once when [snap]. A line
 * out of view is found from the path's measured tops, so even a long way back
 * is one smooth glide rather than a jump.
 *
 * The list can only scroll by whole pixels, so it takes the whole part and the
 * remainder comes back as a shift for the list's layer to apply.
 */
private suspend fun LazyListState.follow(path: LyricsPath, i: Int, posMs: Double, snap: Boolean): Float {
    val line = i.coerceAtLeast(0)
    val items = layoutInfo.visibleItemsInfo
    val here = items.firstOrNull { it.index == line }
    val risen = (path.at(posMs) - path.top(line)).toFloat() // how far the line's top should be above the mark
    if (here == null) {
        val ref = items.firstOrNull()
        if (snap || ref == null) {
            scrollToItem(line)
        } else {
            // Out of view: where it is, from a visible line and the measured tops between them.
            val offset = ref.offset + (path.top(line) - path.top(ref.index))
            animateScrollBy(offset + risen, longCatchUp)
        }
        return 0f
    }
    val step = items.firstOrNull { it.index == line + 1 }?.let { it.offset - here.offset } ?: here.size
    val delta = here.offset + risen
    if (!snap && abs(delta) >= step / 2f) {
        animateScrollBy(delta, if (abs(delta) > layoutInfo.viewportSize.height) longCatchUp else catchUp)
        return 0f
    }
    val whole = delta.roundToInt()
    if (whole != 0) scrollBy(whole.toFloat())
    val now = layoutInfo.visibleItemsInfo.firstOrNull { it.index == line }?.offset ?: return 0f
    return (-risen - now).coerceIn(-1f, 1f)
}

private const val STEP_MS = 50L
private const val MAX_ADJUST_MS = 10_000L

/**
 * An invisible strip down the right edge. Dragging it up or down shows a ruler
 * that moves with the finger and nudges the lyrics' timing: up makes lines
 * come sooner, down later. It hides itself a few seconds after the last touch.
 */
@Composable
private fun TimingRuler(adjust: Long, onAdjust: (Long) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val pxPer100ms = with(density) { 12.dp.toPx() }
    var shown by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) } // bumped on every interaction to restart the hide timer
    val latest by rememberUpdatedState(adjust)
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "ruler")

    LaunchedEffect(shown, touched) {
        if (shown) { delay(2500); shown = false }
    }

    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        // Label and reset, beside the ruler's centre mark.
        if (alpha > 0f) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (adjust == 0L) "0.00s" else "%+.2fs".format(adjust / 1000f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            adjust > 0 -> stringResource(R.string.lyrics_earlier)
                            adjust < 0 -> stringResource(R.string.lyrics_later)
                            else -> stringResource(R.string.lyrics_original_timing)
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (adjust != 0L) {
                        TextButton(onClick = { onAdjust(0); onDone(); touched++ }, enabled = shown) { Text(stringResource(R.string.lyrics_reset)) }
                    }
                }
            }
        }
        val tick = MaterialTheme.colorScheme.onBackground
        val mark = MaterialTheme.colorScheme.primary
        Canvas(
            Modifier
                .width(48.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    var startAdjust = 0L
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { startAdjust = latest; dragged = 0f; shown = true; touched++ },
                        onDragEnd = { onDone(); touched++ },
                        onDragCancel = { onDone(); touched++ },
                    ) { change, dy ->
                        change.consume()
                        dragged -= dy // finger up = positive = lyrics sooner
                        val raw = startAdjust + (dragged / pxPer100ms * 100).toLong()
                        val next = (Math.round(raw.toDouble() / STEP_MS) * STEP_MS).coerceIn(-MAX_ADJUST_MS, MAX_ADJUST_MS)
                        if (next != latest) {
                            if (next / 100 != latest / 100) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAdjust(next)
                        }
                        touched++
                    }
                },
        ) {
            if (alpha == 0f) return@Canvas
            // Tick for value v sits at centre + (v - adjust) scaled, so the ruler moves with the finger.
            val cy = size.height / 2
            val right = size.width - 4.dp.toPx()
            val first = ((adjust - cy / pxPer100ms * 100) / 100).toLong() - 1
            val last = ((adjust + cy / pxPer100ms * 100) / 100).toLong() + 1
            for (k in first..last) {
                val v = k * 100
                if (v < -MAX_ADJUST_MS || v > MAX_ADJUST_MS) continue
                val y = cy + (v - adjust) / 100f * pxPer100ms
                val fade = (1f - kotlin.math.abs(y - cy) / cy).coerceIn(0f, 1f)
                val len = when { v == 0L -> 24.dp.toPx(); v % 1000 == 0L -> 18.dp.toPx(); v % 500 == 0L -> 12.dp.toPx(); else -> 7.dp.toPx() }
                drawLine(tick.copy(alpha = alpha * fade * if (v == 0L) 1f else 0.6f), Offset(right - len, y), Offset(right, y),
                    strokeWidth = if (v == 0L) 3.dp.toPx() else 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
            // Fixed centre mark: where "now" sits.
            drawLine(mark.copy(alpha = alpha), Offset(0f, cy), Offset(right, cy), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * Fades content out over the top and bottom 32dp instead of cutting it off, and
 * moves it by [shift] px. The layer is offscreen, so a fractional shift moves an
 * already-drawn image and the GPU blends it between pixels: the glide is smooth
 * rather than stepping a whole pixel at a time, and costs no layout.
 */
private fun Modifier.fadeEdges(shift: () -> Float) = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
    translationY = shift()
}
    .drawWithContent {
        drawContent()
        val edge = (32.dp.toPx() / size.height).coerceAtMost(0.5f)
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
