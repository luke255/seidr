package eu.bradshaw.seidr.ui.player

import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import eu.bradshaw.seidr.ui.components.ReportItems
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.theme.ArtScheme
import eu.bradshaw.seidr.ui.theme.LocalColourful
import eu.bradshaw.seidr.ui.theme.artScheme
import eu.bradshaw.seidr.ui.theme.monochromeArtScheme
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.components.TrackMenu
import eu.bradshaw.seidr.ui.components.formatDuration
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import eu.bradshaw.seidr.ui.isSelect
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.primaryFocus
import eu.bradshaw.seidr.ui.LocalIsTv
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Off is the repeat icon with a slash through it (Material has no repeat-off
 * icon): the icon's pixels are cleared along a band, then the slash is drawn
 * in that gap, like the stock "…Off" icons.
 */
@Composable
internal fun RepeatButton(mode: Int, onClick: () -> Unit) {
    val off = mode == Player.REPEAT_MODE_OFF
    val tint = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val label = when (mode) {
        Player.REPEAT_MODE_ONE -> stringResource(R.string.player_repeat_one)
        Player.REPEAT_MODE_ALL -> stringResource(R.string.player_repeat_all)
        else -> stringResource(R.string.player_repeat_off)
    }
    IconButton(onClick = onClick) {
        Icon(
            if (mode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, label, tint = tint,
            modifier = if (!off) Modifier else Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val u = size.minDimension / 24f // the icon's 24-unit grid
                    val a = Offset(3f * u, 3f * u)
                    val b = Offset(21f * u, 21f * u)
                    drawLine(Color.Black, a, b, strokeWidth = 5f * u, blendMode = BlendMode.Clear)
                    drawLine(tint, a, b, strokeWidth = 2f * u, cap = StrokeCap.Round)
                },
        )
    }
}


private const val RING_DELAY_MS = 250L
private const val HOLD_MS = 2000

/** A select-button hold on the shuffle button, for remotes and keyboards. */
private class KeyHold {
    var timer: Job? = null
    var filling: Job? = null
    var press: PressInteraction.Press? = null
    var downAt = 0L
    var full = false
}

/**
 * Tap toggles shuffle (turning it back on restores the last shuffled order).
 * Press and hold deals a fresh order, shuffle on either way: after a moment a
 * ring fills round the icon and, since a thumb covers that, a bubble above it
 * fills too. Letting go before it's full cancels. A remote's select button
 * works the same way.
 */
@Composable
internal fun ShuffleButton(on: Boolean, onToggle: () -> Unit, onReshuffle: () -> Unit) {
    val toggle by rememberUpdatedState(onToggle)
    val reshuffle by rememberUpdatedState(onReshuffle)
    val haptics = LocalHapticFeedback.current
    val shuffleLabel = stringResource(R.string.player_shuffle)
    val shuffleOn = stringResource(R.string.player_on)
    val shuffleOff = stringResource(R.string.player_off)
    val toggleShuffleLabel = stringResource(R.string.player_toggle_shuffle)
    val reshuffleLabel = stringResource(R.string.player_reshuffle)
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val interaction = remember { MutableInteractionSource() }
    var bubble by remember { mutableStateOf<Bubble?>(null) }
    var hideBubble by remember { mutableStateOf<Job?>(null) }

    fun done() {
        reshuffle()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        bubble = Bubble.DONE
        hideBubble?.cancel()
        hideBubble = scope.launch { delay(900); bubble = null }
        scope.launch { spin.snapTo(0f); spin.animateTo(360f, tween(450)) }
        scope.launch { fill.snapTo(1f); delay(200); fill.animateTo(0f, tween(250)) }
    }

    fun startFilling(): Job = scope.launch {
        delay(RING_DELAY_MS)
        hideBubble?.cancel()
        bubble = Bubble.HOLDING
        // Wall-clock, not an animation: it has to match the hold
        // timeout even with animator scale changed or off.
        val start = withFrameMillis { it }
        while (true) {
            val t = (withFrameMillis { it } - start).toFloat() / HOLD_MS
            fill.snapTo(t.coerceAtMost(1f))
            if (t >= 1f) break
        }
    }

    fun letGo() {
        if (bubble == Bubble.HOLDING) bubble = null
        scope.launch { fill.animateTo(0f, tween(150)) }
    }

    val keyHold = remember { KeyHold() }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val centre = with(LocalDensity.current) { Offset(24.dp.toPx(), 24.dp.toPx()) }

    Box(
        Modifier
            .size(48.dp)
            .indication(interaction, ripple(bounded = false, radius = 24.dp))
            .onKeyEvent { e ->
                if (!e.isSelect) return@onKeyEvent false
                val h = keyHold
                when (e.type) {
                    KeyEventType.KeyDown -> if (h.timer == null) { // ignore auto-repeat
                        val press = PressInteraction.Press(centre)
                        interaction.tryEmit(press)
                        h.press = press
                        h.downAt = System.currentTimeMillis()
                        h.full = false
                        h.filling = startFilling()
                        h.timer = scope.launch {
                            delay(RING_DELAY_MS + HOLD_MS)
                            h.full = true
                            h.filling?.cancel()
                            interaction.tryEmit(PressInteraction.Release(press))
                            done()
                        }
                    }
                    KeyEventType.KeyUp -> {
                        h.timer?.cancel(); h.timer = null
                        if (!h.full) {
                            h.filling?.cancel()
                            h.press?.let { interaction.tryEmit(PressInteraction.Release(it)) }
                            if (System.currentTimeMillis() - h.downAt < longPressMs) toggle()
                            letGo()
                        }
                        h.press = null
                    }
                }
                true
            }
            .focusable(interactionSource = interaction)
            .semantics {
                role = Role.Button
                contentDescription = shuffleLabel
                stateDescription = if (on) shuffleOn else shuffleOff
                onClick(toggleShuffleLabel) { toggle(); true }
                onLongClick(reshuffleLabel) { done(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val press = PressInteraction.Press(down.position)
                    interaction.tryEmit(press)
                    val filling = startFilling()
                    // null = held all the way; otherwise the release (or null inside for a cancel).
                    val result = withTimeoutOrNull(RING_DELAY_MS + HOLD_MS) { listOf(waitForUpOrCancellation()) }
                    filling.cancel()
                    when {
                        result == null -> {
                            interaction.tryEmit(PressInteraction.Release(press))
                            done()
                            waitForUpOrCancellation()?.consume()
                        }
                        else -> {
                            val up = result.single()
                            if (up == null) interaction.tryEmit(PressInteraction.Cancel(press))
                            else {
                                interaction.tryEmit(PressInteraction.Release(press))
                                up.consume()
                                if (up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) toggle()
                            }
                            letGo()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fill.value > 0f) {
            CircularProgressIndicator(
                progress = { fill.value }, modifier = Modifier.size(44.dp), strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent,
            )
        }
        Icon(
            Icons.Filled.Shuffle, null, Modifier.graphicsLayer { rotationZ = spin.value },
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The bubble is there because a thumb covers the ring; nothing covers it on a TV.
        if (!LocalIsTv.current) bubble?.let { state ->
            val gap = with(LocalDensity.current) { 20.dp.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }) {
                Surface(
                    shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Column(Modifier.width(180.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(if (state == Bubble.DONE) R.string.player_reshuffled else R.string.player_hold_to_reshuffle), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (state == Bubble.DONE) 1f else fill.value }, modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.inversePrimary,
                            trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

/** HOLDING while the ring fills; DONE briefly afterwards. */
private enum class Bubble { HOLDING, DONE }

/**
 * Centred above the anchor with a [gap], kept inside the window. With no room
 * above (the button sits at the top of the Queue screen) it goes to the
 * anchor's left instead, where the thumb isn't.
 */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val above = anchorBounds.top - popupContentSize.height - gap
        if (above >= 0) {
            val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            return IntOffset(x, above)
        }
        val x = (anchorBounds.left - popupContentSize.width - gap).coerceAtLeast(0)
        val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
