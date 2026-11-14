package eu.bradshaw.seidr.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay

/*
 * D-pad (TV remote) and keyboard focus. Compose already moves focus between
 * clickables with the arrow keys; these handle what it doesn't: putting focus
 * somewhere sensible when a screen or overlay appears, and keeping it out of
 * whatever an overlay covers. All of it is a no-op in touch mode.
 */

val InputModeManager.keys: Boolean get() = inputMode == InputMode.Keyboard

/** The select button on a remote, or Enter on a keyboard. */
val KeyEvent.isSelect: Boolean get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** A group that remembers its last focused child, focused from outside through [requester]. */
fun Modifier.focusArea(requester: FocusRequester): Modifier = focusRequester(requester).focusRestorer().focusGroup()

/**
 * Focuses the group behind [requester] (its restored child, else its first),
 * retrying while its content loads. Stops once anything inside has focus.
 */
suspend fun FocusRequester.focusWhenReady(hasFocus: () -> Boolean = { false }) {
    repeat(40) {
        if (hasFocus() || runCatching { requestFocus() }.getOrDefault(false)) return
        delay(50)
    }
}

/**
 * For full-screen overlays (Now Playing, the queue): while [active], focus moves
 * in (to [primary] if given) when it becomes active and the arrow keys can't leave, so it never lands
 * on the screen hidden underneath.
 */
@Composable
fun Modifier.overlayFocus(active: Boolean, primary: FocusRequester? = null): Modifier {
    val requester = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    val trap by rememberUpdatedState(active)
    val inside = remember { booleanArrayOf(false) }
    LaunchedEffect(active) {
        if (!active || !input.keys) return@LaunchedEffect
        if (primary != null) repeat(20) {
            if (runCatching { primary.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
        requester.focusWhenReady { inside[0] }
    }
    return this
        .onFocusChanged { inside[0] = it.hasFocus }
        .focusRequester(requester)
        .focusProperties {
            onExit = {
                // Arrow keys stay in; programmatic moves (another overlay opening) may leave.
                if (trap && requestedFocusDirection != FocusDirection.Enter && requestedFocusDirection != FocusDirection.Exit) cancelFocusChange()
            }
        }
        .focusGroup()
        // A group of its own: the restorer's exit hook would otherwise replace the trap's.
        .focusRestorer()
        .focusGroup()
}

/**
 * Where focus goes when a screen opens: its main action (Shuffle play on a
 * collection, the first playlist on Home). Provided per back-stack entry.
 */
val LocalPrimaryFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Marks this as the screen's [LocalPrimaryFocus]. */
@Composable
fun Modifier.primaryFocus(): Modifier = LocalPrimaryFocus.current?.let { focusRequester(it) } ?: this

/**
 * A ring outside the shape while focused. Filled buttons need it: the ripple's
 * focus layer barely changes a coloured background.
 */
@Composable
fun Modifier.focusRing(shape: Shape, color: Color = MaterialTheme.colorScheme.onSurface, width: Dp = 3.dp, gap: Dp = 3.dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = width.toPx()
                val inset = gap.toPx() + w / 2
                val outline = shape.createOutline(Size(size.width + 2 * inset, size.height + 2 * inset), layoutDirection, this)
                translate(-inset, -inset) { drawOutline(outline, color, style = Stroke(w)) }
            }
        }
}

/**
 * For text fields, with a remote or keyboard: focus stops on the field's outline
 * and only goes into it (bringing up the on-screen keyboard) on select. Moving
 * through a form would otherwise pop the keyboard up at every field.
 */
@Composable
fun Modifier.selectToEdit(shape: Shape = RoundedCornerShape(4.dp)): Modifier {
    if (!LocalInputModeManager.current.keys) return this
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    return this
        .focusRing(shape)
        .onKeyEvent { e ->
            if (!editing && e.isSelect) {
                if (e.type == KeyEventType.KeyUp) { editing = true; field.requestFocus() }
                true
            } else false
        }
        .focusable()
        // Everything after applies to the text field's own focus target.
        .focusRequester(field)
        .focusProperties { canFocus = editing }
        .onFocusChanged { if (!it.hasFocus) editing = false }
}

/** The last focused item on one screen, so Back can return to it however deep it was. */
class FocusMemory {
    var last: FocusRequester? = null

    /** Focuses [last] if it's still on screen. */
    fun restore(): Boolean = last?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
}

val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/** Put on list items (before their clickable) so the screen's [FocusMemory] can come back to them. */
@Composable
fun Modifier.rememberedFocus(): Modifier {
    val memory = LocalFocusMemory.current ?: return this
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.last = requester }
}

/**
 * Scrolls only as far as needed to show the focused item, keeping [marginPx]
 * so the next one peeks in. Compose's TV default instead pins focus 30% down
 * every list, which scrolled a collection's artwork away as soon as Play was
 * focused.
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeBringIntoView(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = marginPx.coerceAtMost(((containerSize - size) / 2).coerceAtLeast(0f))
        val before = offset - margin                         // < 0: too near the start
        val after = offset + size - (containerSize - margin) // > 0: too near the end
        return when {
            before < 0 && after > 0 -> 0f // fills the window already
            before < 0 -> before
            after > 0 -> after
            else -> 0f
        }
    }
}

/**
 * For menus opened by holding select: the release would otherwise land on the
 * menu's first item and click it. Ignores a select release unless its press
 * (not an auto-repeat) happened inside the menu.
 */
@Composable
fun Modifier.ignoreHeldSelect(): Modifier {
    val pressed = remember { booleanArrayOf(false) }
    return onPreviewKeyEvent { e ->
        if (!e.isSelect) return@onPreviewKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> { if (e.nativeKeyEvent.repeatCount == 0) pressed[0] = true; !pressed[0] }
            KeyEventType.KeyUp -> !pressed[0]
            else -> false
        }
    }
}
