package eu.bradshaw.seidr.ui

import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.coroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawBehind
import eu.bradshaw.seidr.ui.player.lerpScheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import eu.bradshaw.seidr.ui.components.LocalBottomChrome
import eu.bradshaw.seidr.ui.components.LocalWideShell
import eu.bradshaw.seidr.ui.components.SettingsButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.ui.components.AddToPlaylistDialog
import eu.bradshaw.seidr.ui.player.NowPlayingScreen
import eu.bradshaw.seidr.ui.player.QueueScreen
import eu.bradshaw.seidr.ui.screens.AlbumScreen
import eu.bradshaw.seidr.ui.screens.ArtistScreen
import eu.bradshaw.seidr.ui.screens.DownloadsScreen
import eu.bradshaw.seidr.ui.screens.HomeScreen
import eu.bradshaw.seidr.ui.screens.LibraryScreen
import eu.bradshaw.seidr.ui.screens.PlaylistScreen
import eu.bradshaw.seidr.ui.screens.SearchScreen
import eu.bradshaw.seidr.ui.screens.SettingsScreen

private data class TabSpec(val tab: Tab, @androidx.annotation.StringRes val label: Int, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    TabSpec(Tab.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    TabSpec(Tab.SEARCH, R.string.nav_search, Icons.Filled.Search, Icons.Outlined.Search),
    TabSpec(Tab.LIBRARY, R.string.nav_library, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)

@Composable
private fun RouteContent(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Search -> SearchScreen()
        Route.Library -> LibraryScreen()
        is Route.Playlist -> PlaylistScreen(route.id)
        Route.Loved -> eu.bradshaw.seidr.ui.screens.LovedScreen()
        is Route.Album -> AlbumScreen(route.id)
        is Route.Artist -> ArtistScreen(route.id)
        Route.Downloads -> DownloadsScreen()
        Route.Visualiser -> eu.bradshaw.seidr.ui.screens.VisualiserSettingsScreen()
        Route.Settings -> SettingsScreen()
        Route.SignInDevice -> eu.bradshaw.seidr.ui.pair.SignInDeviceScreen()
        Route.Licences -> eu.bradshaw.seidr.ui.screens.LicencesScreen()
        Route.WhatsNew -> eu.bradshaw.seidr.ui.screens.WhatsNewScreen()
        Route.Log -> eu.bradshaw.seidr.ui.screens.LogScreen()
        is Route.PlaylistHistory -> eu.bradshaw.seidr.ui.screens.PlaylistHistoryScreen(route.id)
        Route.DeletedPlaylists -> eu.bradshaw.seidr.ui.screens.DeletedPlaylistsScreen()
        is Route.SmartRules -> eu.bradshaw.seidr.ui.screens.SmartRulesScreen(route.id, route.name)
        is Route.LicenceText -> eu.bradshaw.seidr.ui.screens.LicenceTextScreen(route.title, route.file)
    }
}

/**
 * Per back-stack entry: its whole area (which remembers the last focused
 * child) and its main action ([LocalPrimaryFocus]), so a remote can be sent
 * into a screen.
 */
private class EntryFocus {
    class Targets(val area: FocusRequester, val primary: FocusRequester, val memory: FocusMemory)
    private val map = mutableMapOf<String, Targets>()
    operator fun get(entry: Entry): Targets = map.getOrPut(entry.id) { Targets(FocusRequester(), FocusRequester(), FocusMemory()) }
}

/** Each back-stack entry's [WashState]. */
private val LocalWashes = androidx.compose.runtime.staticCompositionLocalOf<EntryWashes?> { null }

private class EntryWashes {
    private val map = mutableMapOf<String, WashState>()
    operator fun get(entry: Entry): WashState = map.getOrPut(entry.id) { WashState() }
}

private data class Shown(val tab: Tab, val size: Int)

/** Focus somewhere at once (the back button), then the main action once it has loaded. */
private suspend fun EntryFocus.Targets.focusOpened() {
    area.focusWhenReady()
    repeat(40) {
        if (runCatching { primary.requestFocus() }.getOrDefault(false)) return
        kotlinx.coroutines.delay(50)
    }
}

/**
 * Back where focus was. A screen coming back with its tab is composed afresh
 * and its lists lay out a moment later, so then [retry] for a little while.
 */
private suspend fun EntryFocus.Targets.restoreFocus(retry: Boolean): Boolean {
    repeat(if (retry) 10 else 1) { i ->
        if (memory.restore()) return true
        if (i < 9) kotlinx.coroutines.delay(30)
    }
    return false
}

/** Renders one back-stack entry with its own ViewModels and saved UI state. */
@Composable
private fun EntryHost(entry: Entry, saveable: SaveableStateHolder, focus: EntryFocus) {
    val targets = focus[entry]
    saveable.SaveableStateProvider(entry.id) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides entry, LocalPrimaryFocus provides targets.primary, LocalFocusMemory provides targets.memory,
            LocalWash provides LocalWashes.current?.get(entry),
        ) {
            Box(Modifier.fillMaxSize().focusArea(targets.area)) { RouteContent(entry.route) }
        }
    }
}

/**
 * The app shell. Cover screen (narrow): bottom nav. Inner screen (wide):
 * navigation rail, the tab's root list beside the open detail. Both carry a
 * Now Playing item after the tabs; Now Playing and the queue are full-screen
 * overlays on both.
 */
@Composable
fun MainScreen(graph: Graph, nav: Navigator) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Plain helpers (pluralSongs, formatBytes, ...) read their strings from this.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val actions = remember(nav) { AppActions(graph, nav, snackbar, scope) }
    androidx.compose.runtime.LaunchedEffect(actions) { graph.connect.messages.collect { actions.toast(it) } }
    val saveable = rememberSaveableStateHolder()
    val focus = remember { EntryFocus() }
    val washes = remember { EntryWashes() }
    val mainFocus = remember { FocusRequester() }
    val nowPlayingPrimary = remember { FocusRequester() } // play/pause
    val queuePrimary = remember { FocusRequester() } // the "Now playing" row
    val input = LocalInputModeManager.current
    val overlay = actions.nowPlayingOpen || actions.queueOpen

    androidx.compose.runtime.LaunchedEffect(nav.requestNowPlaying) {
        if (nav.requestNowPlaying) { actions.nowPlayingOpen = true; nav.requestNowPlaying = false }
    }

    // Remote/keyboard: opening a screen focuses its main action, going back returns
    // to where you were, switching tabs leaves focus on the tab. Closing an overlay
    // returns focus to where it was.
    var shown by remember { mutableStateOf<Shown?>(null) }
    androidx.compose.runtime.LaunchedEffect(nav.top) {
        val before = shown
        shown = Shown(nav.tab, nav.stack.size)
        val into = nav.focusIntoTab.also { nav.focusIntoTab = null }
        if (overlay || !input.keys || (before != null && before.tab != nav.tab && into == null)) return@LaunchedEffect
        val targets = focus[nav.top]
        // The focused control has just gone with its tab, and its focus is handed on (to the
        // first tab) after this frame; take it into the screen once that's happened.
        if (into != null) androidx.compose.runtime.withFrameNanos {}
        if (before == null || into == TabFocus.OPENED || nav.stack.size > before.size) targets.focusOpened()
        else if (!targets.restoreFocus(retry = into != null)) targets.area.focusWhenReady()
    }
    var hadOverlay by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(overlay) {
        if (hadOverlay && !overlay && input.keys) mainFocus.focusWhenReady()
        hadOverlay = overlay
    }

    // How far a back swipe has pulled Now Playing / the queue down (0..1). Left there while it
    // slides out (it's already off screen), and put back when it next opens.
    val npSwipe = remember { Animatable(0f) }
    val queueSwipe = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(actions.nowPlayingOpen) { if (actions.nowPlayingOpen) npSwipe.snapTo(0f) }
    androidx.compose.runtime.LaunchedEffect(actions.queueOpen) { if (actions.queueOpen) queueSwipe.snapTo(0f) }

    // Now Playing and the queue slide over the screens; once one covers them, the screens
    // underneath stay composed but aren't drawn. Drawn anyway, a TV's GPU spent most of each
    // frame of a cover slide on Home's covers hidden under Now Playing.
    val npShown = remember { MutableTransitionState(false) }
    val queueShown = remember { MutableTransitionState(false) }
    if (npShown.targetState != actions.nowPlayingOpen) npShown.targetState = actions.nowPlayingOpen
    if (queueShown.targetState != actions.queueOpen) queueShown.targetState = actions.queueOpen
    val covered by remember {
        derivedStateOf {
            (npShown.isIdle && npShown.currentState && npSwipe.value == 0f) ||
                (queueShown.isIdle && queueShown.currentState && queueSwipe.value == 0f)
        }
    }

    CompositionLocalProvider(LocalActions provides actions, LocalWashes provides washes) {
        BoxWithConstraints(Modifier.fillMaxSize().drawWithContent { if (!covered) drawContent() }
            .background(MaterialTheme.colorScheme.background).focusArea(mainFocus)) {
            val wide = maxWidth >= 600.dp
            // How far a back swipe has slid the top screen off (0..1).
            val swipe = remember { Animatable(0f) }
            var swiping by remember { mutableStateOf(false) }
            PredictiveBackHandler(enabled = actions.queueOpen || actions.nowPlayingOpen || nav.stack.size > 1 || nav.tab != Tab.HOME) { events ->
                if (actions.queueOpen || actions.nowPlayingOpen) {
                    // The sheet follows the swipe down, then carries on off the bottom.
                    followBack(if (actions.queueOpen) queueSwipe else npSwipe, scope) { events.collect(it) }
                    if (actions.queueOpen) actions.queueOpen = false else actions.nowPlayingOpen = false
                    return@PredictiveBackHandler
                }
                // Like iOS: the screen follows the finger off to the right, uncovering the one
                // below (from a tab's root, Home).
                followBack(swipe, scope, onStart = { swiping = true }, onCancelled = { swiping = false }) { events.collect(it) }
                nav.pop()
                swiping = false
                swipe.snapTo(0f)
            }
            if (wide) WideLayout(nav, saveable, focus, actions, snackbar, if (swiping) swipe.value else null)
            else NarrowLayout(nav, saveable, focus, actions, snackbar, if (swiping) swipe.value else null)
        }
        // Now Playing and the queue cover the whole window on both screens.
        // A drag down pulls Now Playing with it too; outside its layer, so the finger isn't measured from a moving sheet.
        val npPull = Modifier.pullDownToClose(npSwipe) { actions.nowPlayingOpen = false }
            .graphicsLayer { translationY = npSwipe.value * size.height }
        val queuePull = Modifier.graphicsLayer { translationY = queueSwipe.value * size.height }
        AnimatedVisibility(npShown, npPull, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            CompositionLocalProvider(LocalPrimaryFocus provides nowPlayingPrimary) {
                NowPlayingScreen(onClose = { actions.nowPlayingOpen = false }, onQueue = { actions.queueOpen = true },
                    modifier = Modifier.overlayFocus(actions.nowPlayingOpen && !actions.queueOpen, nowPlayingPrimary))
            }
        }
        AnimatedVisibility(queueShown, queuePull, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            CompositionLocalProvider(LocalPrimaryFocus provides queuePrimary) {
                QueueScreen(onClose = { actions.queueOpen = false }, modifier = Modifier.overlayFocus(actions.queueOpen, queuePrimary))
            }
        }
        AddToPlaylistDialog()
        eu.bradshaw.seidr.ui.screens.CheckCode(graph.connect, graph.settings.server.collectAsStateWithLifecycle().value?.username)
    }
}

/**
 * Drag down to pull a full-screen sheet with the finger ([pull]: how far, 0..1 of its height),
 * and let go far enough down, or flung down, to close it; otherwise it springs back. Children
 * see the drag first, so sideways pagers, the scrubber and scrolling lyrics keep theirs.
 */
private fun Modifier.pullDownToClose(pull: Animatable<Float, AnimationVector1D>, onClose: () -> Unit) = pointerInput(pull) {
    coroutineScope {
        val tracker = VelocityTracker()
        var total = 0f
        fun settle(close: Boolean) = launch {
            if (close) {
                pull.animateTo(1f, tween(160))
                onClose()
            } else pull.animateTo(0f, tween(200))
        }
        detectVerticalDragGestures(
            onDragStart = { total = 0f; tracker.resetTracking() },
            onDragEnd = {
                val v = tracker.calculateVelocity().y
                val fling = 1_000.dp.toPx()
                settle(v > fling || (total > 120.dp.toPx() && v > -fling / 2))
            },
            onDragCancel = { settle(false) },
        ) { change, dy ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            total = (total + dy).coerceAtLeast(0f)
            launch { pull.snapTo(total / size.height) }
        }
    }
}

/** The nav destinations, as slim icon-and-label items: bold and filled when selected, no pill. */
@Composable
private fun NavItem(spec: TabSpec, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    NavItemLayout(
        if (selected) spec.selected else spec.unselected, stringResource(spec.label), color, bold = selected,
        modifier.clip(RoundedCornerShape(12.dp)).selectable(selected, onClick = onClick, role = Role.Tab),
    )
}

/**
 * Opens Now Playing; sits after the tabs. Filled and in the theme colour while
 * something plays, dimmed and disabled while the queue is empty.
 */
@Composable
private fun NowPlayingItem(actions: AppActions, modifier: Modifier = Modifier) {
    val ui by actions.player.ui.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val has = ui.current != null
    val color = when {
        ui.isPlaying -> cs.primary
        has -> cs.onSurfaceVariant
        else -> cs.onSurfaceVariant.copy(alpha = 0.38f)
    }
    NavItemLayout(
        if (ui.isPlaying) Icons.Filled.PlayCircle else Icons.Outlined.PlayCircle, stringResource(R.string.nav_now_playing), color, bold = false,
        modifier.clip(RoundedCornerShape(12.dp))
            .clickable(enabled = has, role = Role.Button) { actions.nowPlayingOpen = true },
    )
}

@Composable
private fun NavItemLayout(icon: ImageVector, label: String, color: Color, bold: Boolean, modifier: Modifier) {
    Column(
        modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = color)
        Text(
            // Shrinks to fit rather than wrapping (which drops the second word): the
            // rail is narrow, and "Now Playing" outgrows it at larger font scales.
            label, color = color, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Follows a back swipe's progress (0..1) with [pull], and once it's committed carries it on
 * to 1; cancelled, it springs back to 0 and the cancellation carries on. A remote's Back has
 * no progress: then nothing moves (and [onStart] isn't called) and the caller just closes.
 * [collect] collects the handler's events into the collector it's given (lint wants to see
 * each handler collect its own).
 */
internal suspend fun followBack(
    pull: Animatable<Float, AnimationVector1D>, scope: CoroutineScope,
    onStart: () -> Unit = {}, onCancelled: () -> Unit = {},
    collect: suspend (FlowCollector<BackEventCompat>) -> Unit,
) {
    var moved = false
    try {
        collect {
            if (!moved) { moved = true; onStart() }
            pull.snapTo(it.progress)
        }
        if (moved) pull.animateTo(1f, tween(160))
    } catch (e: CancellationException) {
        scope.launch { pull.animateTo(0f, tween(200)); onCancelled() }
        throw e
    }
}

/**
 * The [top] page, and mid back-swipe ([swipe], 0..1) the one [below] it too: the top
 * follows the finger off to the right, uncovering the one below, which comes in from
 * [parallax] of the width to the left and out of shadow. Keyed by page, so the one below
 * carries on (rather than starting over) once the swipe takes the top one away.
 */
@Composable
internal fun <T : Any> SwipeStack(
    top: T, below: T?, swipe: Float?, parallax: Float, shape: Shape,
    page: @Composable (T) -> Modifier, content: @Composable (T) -> Unit,
) {
    val shown = if (swipe != null && below != null) listOf(below, top) else listOf(top)
    shown.forEach { entry ->
        key(entry) {
            val under = swipe != null && entry !== top
            val p = swipe ?: 0f
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        if (swipe == null) return@graphicsLayer
                        translationX = if (under) -parallax * size.width * (1f - p) else p * size.width
                        if (!under) { shadowElevation = 24.dp.toPx(); this.shape = shape }
                    }
                    .then(page(entry))
                    .drawWithContent {
                        drawContent()
                        if (under) drawRect(Color.Black.copy(alpha = 0.35f * (1f - p)))
                    },
            ) { content(entry) }
        }
    }
}

/**
 * Cover screen: the screen fills the window and scrolls under the floating
 * chrome (a slim nav bar on a fade to the background).
 * Screens pad their ends by [LocalBottomChrome] so the last row clears it.
 */
@Composable
private fun NarrowLayout(
    nav: Navigator, saveable: SaveableStateHolder, focus: EntryFocus, actions: AppActions, snackbar: SnackbarHostState,
    /** While a back swipe is under way, how far the top screen has slid off (0..1). */
    swipe: Float?,
) {
    val density = LocalDensity.current
    var chrome by remember { mutableStateOf(0.dp) }
    val bg = MaterialTheme.colorScheme.background
    val washes = LocalWashes.current!!
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomChrome provides chrome) {
            SwipeStack(nav.top, nav.below, swipe, parallax = 0.25f, shape = RectangleShape,
                page = { Modifier.background(bg).background(washBrush()).artBackground(washes[it]) }) { entry ->
                Box(Modifier.fillMaxSize().statusBarsPadding()) { EntryHost(entry, saveable, focus) }
            }
        }
        // The nav bar follows the top page, and mid back-swipe moves toward the
        // one coming back in step with the slide.
        val topWash = washes[nav.top]
        val underWash = nav.below?.takeIf { swipe != null }?.let { washes[it] } ?: topWash
        val swipeP = if (underWash === topWash) 0f else swipe ?: 0f
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { chrome = with(density) { it.height.toDp() } }
                .drawBehind {
                    // Fades into the page's own background (an album's art colours).
                    val under = lerp(topWash.bottom(bg), underWash.bottom(bg), swipeP)
                    drawRect(Brush.verticalGradient(0f to under.copy(alpha = 0f), 0.3f to under.copy(alpha = 0.9f), 0.5f to under))
                }
                .navigationBarsPadding()
                .padding(top = 24.dp),
        ) {
            // Tinted like the page under it (an album's art colours).
            val base = MaterialTheme.colorScheme
            val navScheme = lerpScheme(rememberPageScheme(topWash, base), rememberPageScheme(underWash, base), swipeP)
            MaterialTheme(colorScheme = navScheme) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(top = 4.dp)) {
                    tabs.forEach { t -> NavItem(t, nav.tab == t.tab, { nav.select(t.tab) }, Modifier.weight(1f).fillMaxHeight()) }
                    NowPlayingItem(actions, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = chrome))
    }
}

/**
 * Inner screen (and TV): a rail (tabs, Now Playing, Settings at the foot), then
 * rounded panes (the tab's root list beside the open detail) on a darker
 * ground. Nothing overlaps, so a remote's focus never hides.
 */
@Composable
private fun WideLayout(
    nav: Navigator, saveable: SaveableStateHolder, focus: EntryFocus, actions: AppActions, snackbar: SnackbarHostState,
    /** While a back swipe is under way, how far the top screen has slid off (0..1). */
    swipe: Float?,
) {
    val cs = MaterialTheme.colorScheme
    val pane = RoundedCornerShape(12.dp)
    val washes = LocalWashes.current!!
    val paneBrush = washBrush(height = 360.dp, themeStrength = 0.7f)
    Box(Modifier.fillMaxSize().background(lerp(cs.background, Color.Black, 0.45f))) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 4.dp, end = 8.dp, bottom = 8.dp)) {
            Column(
                Modifier.width(80.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                tabs.forEach { t ->
                    NavItem(t, nav.tab == t.tab, { nav.select(t.tab) }, Modifier.width(76.dp).padding(vertical = 2.dp).height(60.dp))
                }
                NowPlayingItem(actions, Modifier.width(76.dp).padding(vertical = 2.dp).height(60.dp))
                Spacer(Modifier.weight(1f))
                SettingsButton(Modifier.width(76.dp).height(60.dp), label = true)
            }
            CompositionLocalProvider(LocalWideShell provides true) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    // Unfolded in portrait is only ~700dp: show one pane rather than squeeze two.
                    val detail = nav.stack.size > 1
                    val split = detail && maxWidth >= 720.dp
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // One pane: the whole stack, as on the cover screen. Split: the root on its
                        // own, the rest in the detail pane, whose last page slides off to the ground.
                        // The same call either way, so the root carries on when the split closes.
                        val page: @Composable (Entry) -> Modifier = {
                            Modifier.clip(pane).background(cs.background).background(paneBrush).artBackground(washes[it])
                        }
                        Box(Modifier.fillMaxHeight().weight(if (split) 0.4f else 1f)) {
                            SwipeStack(if (split) nav.root else nav.top, nav.below.takeUnless { split }, swipe, 0f, pane, page) {
                                EntryHost(it, saveable, focus)
                            }
                        }
                        if (split) Box(Modifier.fillMaxHeight().weight(0.6f)) {
                            SwipeStack(nav.top, nav.below.takeIf { nav.stack.size > 2 }, swipe, 0f, pane, page) {
                                EntryHost(it, saveable, focus)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}
