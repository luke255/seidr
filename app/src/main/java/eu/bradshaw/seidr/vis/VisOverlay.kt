package eu.bradshaw.seidr.vis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Typography
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import eu.bradshaw.seidr.playback.QueueEntry
import eu.bradshaw.seidr.ui.AppActions
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Navigator
import eu.bradshaw.seidr.ui.player.LyricsPane
import eu.bradshaw.seidr.ui.player.LyricsState
import eu.bradshaw.seidr.ui.player.ART_CHANGE_MS
import eu.bradshaw.seidr.ui.player.artOf
import eu.bradshaw.seidr.ui.player.ArtworkPager
import eu.bradshaw.seidr.ui.player.rememberJustBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import eu.bradshaw.seidr.ui.player.artFadeProgress
import eu.bradshaw.seidr.ui.player.rememberSchemeFade
import eu.bradshaw.seidr.ui.player.rememberArtColours
import eu.bradshaw.seidr.ui.player.rememberLyrics
import eu.bradshaw.seidr.ui.theme.LocalColourful
import eu.bradshaw.seidr.ui.theme.artScheme
import eu.bradshaw.seidr.ui.theme.monochromeArtScheme
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.playback.PlayerConnection
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.ui.theme.SeidrTheme
import eu.bradshaw.seidr.ui.theme.ThemeChoice
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Keeps white text legible over whatever a preset draws: a generous dark halo, no panel. */
private val glow = Shadow(Color.Black, offset = Offset(0f, 1.5f), blurRadius = 16f)

/** Every text style with [glow], so borrowed Now Playing pieces read over the preset too. */
private fun Typography.glowing(): Typography {
    fun TextStyle.g() = copy(shadow = glow)
    return copy(
        displayLarge.g(), displayMedium.g(), displaySmall.g(), headlineLarge.g(), headlineMedium.g(), headlineSmall.g(),
        titleLarge.g(), titleMedium.g(), titleSmall.g(), bodyLarge.g(), bodyMedium.g(), bodySmall.g(),
        labelLarge.g(), labelMedium.g(), labelSmall.g(),
    )
}

/** An icon with a soft dark shadow under it, like the text's. */
@Composable
private fun GlowIcon(icon: ImageVector, description: String?, modifier: Modifier = Modifier, tint: Color = Color.White, size: Dp = 24.dp) {
    Box(modifier) {
        Icon(icon, null, Modifier.size(size).offset(y = 1.dp).blur(5.dp, BlurredEdgeTreatment.Unbounded), tint = Color.Black.copy(alpha = 0.85f))
        Icon(icon, description, Modifier.size(size), tint = tint)
    }
}

/** How long a play/pause or skip sign stays up. */
private const val PULSE_MS = 1_200L

/** A translucent dark panel, for controls. */
private fun Modifier.panel(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)) =
    background(Color.Black.copy(alpha = 0.45f), shape)

/**
 * The visualiser's pages, none showing at first. Up (a swipe or the D-pad)
 * goes on to the next, down back (both round through none); Back puts the
 * page away. Sideways still skips tracks, except that on [controls] pages,
 * which have buttons for a remote to reach, the D-pad moves between those.
 * Pages stay until put away, and sit in the middle, over the preset with no
 * panel behind them.
 */
enum class VisPage(val controls: Boolean = false) {
    PRESET(controls = true), TRACK, LYRICS, QUEUE, LOGO;

    companion object {
        /** [by] pages on from [from] (null: none), round through none. */
        fun turn(from: VisPage?, by: Int): VisPage? {
            val ring = listOf(null) + entries
            return ring[Math.floorMod(ring.indexOf(from) + by, ring.size)]
        }
    }
}

/**
 * Everything over the visualiser: the track card (bottom left); the [page]
 * showing, if any ([VisPage]); a passing [message]; a play/pause or skip
 * [pulse]; and the touch gestures (see [VisualiserActivity]). With [preview]
 * (a preset's path), just its name. On a headset, whose hand tracking has no
 * Back, a close button shows with any page (and in a preview).
 */
@Composable
fun VisOverlay(
    graph: Graph,
    preview: String?,
    preset: String?,
    page: VisPage?,
    message: String?,
    onTurn: (by: Int) -> Unit,
    onPage: (VisPage?) -> Unit,
    onHidePreset: () -> Unit,
    onPresetStep: (forward: Boolean) -> Unit,
    onMessageShown: () -> Unit,
    pulse: Pulse?,
    onPlayPause: () -> Unit,
    onSkip: (next: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val theme by graph.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = remember { isTv(context) }
    val headset = remember { XrVisualiserActivity.available(context) }
    val player = graph.player
    // The Now Playing pieces the pages borrow look for these.
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(graph, Navigator(), SnackbarHostState(), scope) }
    CompositionLocalProvider(LocalIsTv provides tv, LocalActions provides actions) {
        SeidrTheme(theme) {
            val density = LocalDensity.current
            val swipe = with(density) { 80.dp.toPx() }
            // The system's side-edge gesture zones (back), at least 32 dp.
            val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
            val minEdge = with(density) { 32.dp.toPx() }
            val edgeL = maxOf(WindowInsets.systemGestures.getLeft(density, dir).toFloat(), minEdge)
            val edgeR = maxOf(WindowInsets.systemGestures.getRight(density, dir).toFloat(), minEdge)
            Box(
                Modifier.fillMaxSize()
                    // A tap plays/pauses; a hold (with no page up) shows the preset's page.
                    .pointerInput(preview, page) {
                        if (preview != null) return@pointerInput
                        detectTapGestures(
                            onTap = { onPlayPause() },
                            onLongPress = { if (page == null) onPage(VisPage.PRESET) },
                        )
                    }
                    // Up and down turn the pages. Sideways skips, page or not (left for the
                    // next, like the cover in Now Playing).
                    .pointerInput(preview, page) {
                        if (preview != null) return@pointerInput
                        var dx = 0f
                        var dy = 0f
                        var fromEdge = false
                        detectDragGestures(
                            // A swipe in from a side edge is the system's back gesture, not a skip.
                            onDragStart = { at -> dx = 0f; dy = 0f; fromEdge = at.x < edgeL || at.x > size.width - edgeR },
                            onDragEnd = {
                                if (fromEdge) return@detectDragGestures
                                if (abs(dx) > abs(dy)) {
                                    if (abs(dx) > swipe) onSkip(dx < 0)
                                } else if (abs(dy) > swipe) {
                                    onTurn(if (dy < 0) 1 else -1)
                                }
                            },
                        ) { change, delta -> change.consume(); dx += delta.x; dy += delta.y }
                    },
            ) {
                val edge = if (tv) PaddingValues(horizontal = 48.dp, vertical = 32.dp) else PaddingValues(24.dp)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(edge)) {
                    if (preview != null) {
                        PresetLabel(preview, Modifier.align(Alignment.BottomStart))
                    } else {
                        val showInfo by graph.visPrefs.showInfo.collectAsStateWithLifecycle()
                        // The track and queue pages say it themselves.
                        val card = (showInfo || page == VisPage.PRESET) && page != VisPage.TRACK && page != VisPage.QUEUE
                        TrackCard(player, visible = card, tv, Modifier.align(Alignment.BottomStart))
                        // Pages sit in the middle, clear of the card and the close button by the same
                        // amount above and below, so they stay centred.
                        val clear = maxOf(if (card) 96.dp else 0.dp, if (headset) 64.dp else 0.dp)
                        val middle = Modifier.fillMaxSize().padding(vertical = clear)
                        // Colours follow the track's art, like Now Playing's (not under Neutral).
                        val ui by player.ui.collectAsStateWithLifecycle()
                        val colourful = LocalColourful.current
                        val art = rememberArtColours(if (colourful) ui.current?.coverArt else null)
                        val base = MaterialTheme.colorScheme
                        val artTarget = remember(art, base) {
                            art?.let(::artScheme)?.scheme ?: if (colourful) monochromeArtScheme.scheme else base
                        }
                        // Borrowed pieces' greys go white: a dim grey vanishes over a bright preset.
                        val scheme = rememberSchemeFade(artTarget, Color.Transparent, artFadeProgress(false)).scheme.copy(
                            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = 0.85f),
                        )
                        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography.glowing()) {
                            AnimatedContent(page, Modifier.fillMaxSize(), label = "page",
                                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) }) { p ->
                                when (p) {
                                    VisPage.PRESET -> if (preset != null) {
                                        Box(middle, contentAlignment = Alignment.Center) { PresetBar(graph.visPrefs, preset, onHidePreset, onPresetStep) }
                                    }
                                    VisPage.TRACK -> TrackPage(player, tv, middle)
                                    VisPage.LYRICS -> LyricsPage(player, middle)
                                    VisPage.QUEUE -> QueuePage(player, middle)
                                    // The logo is the engine's, drawn into the picture (LogoSprite).
                                    VisPage.LOGO, null -> Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                        PageDots(page, Modifier.align(Alignment.CenterEnd))
                    }
                    if (headset) {
                        AnimatedVisibility(page != null || preview != null, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                            IconButton(onClick = onClose, Modifier.size(52.dp).panel(CircleShape),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                                Icon(Icons.Filled.Close, stringResource(R.string.vis_close))
                            }
                        }
                    }
                    if (message != null) {
                        Text(
                            message, style = MaterialTheme.typography.bodyLarge.copy(shadow = glow), color = Color.White,
                            textAlign = TextAlign.Center,
                            // Under the preset bar, if it's showing.
                            modifier = Modifier.align(Alignment.Center).padding(top = 160.dp, start = 24.dp, end = 24.dp),
                        )
                        LaunchedEffect(message) { delay(3_500); onMessageShown() }
                    }
                }
                // Over everything, pages included. Not a skip's on the track page: the cover's slide says it.
                val skip = pulse?.kind == Pulse.Kind.NEXT || pulse?.kind == Pulse.Kind.PREVIOUS
                if (pulse != null && !(skip && page == VisPage.TRACK)) PulseSign(pulse, Modifier.align(Alignment.Center))
            }
        }
    }
}

/** How long the page dots stay after a page turns. */
private const val DOTS_MS = 2_000L

/** Which page is up: a dot for each, down the right-hand edge, fading out a moment after each turn. */
@Composable
private fun PageDots(page: VisPage?, modifier: Modifier) {
    var shown by remember { mutableStateOf(false) }
    // The last page up, so the dots don't jump as they fade on the way to none.
    var last by remember { mutableStateOf(page) }
    LaunchedEffect(page) {
        if (page == null) { shown = false; return@LaunchedEffect }
        last = page
        shown = true
        delay(DOTS_MS)
        shown = false
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut(tween(600))) {
        Column(Modifier.padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in VisPage.entries) {
                Box(Modifier.size(if (p == last) 8.dp else 6.dp).shadow(4.dp, CircleShape)
                    .background(Color.White.copy(alpha = if (p == last) 0.95f else 0.4f), CircleShape))
            }
        }
    }
}

/** Now Playing's artwork and title, big, in the middle. */
@Composable
private fun TrackPage(player: PlayerConnection, tv: Boolean, modifier: Modifier) {
    val ui by player.ui.collectAsStateWithLifecycle()
    val cur = ui.current ?: return
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val wide = maxWidth > maxHeight * 1.25f
        // Room round the cover for its shadow: the pager clips to its bounds, and a 24dp
        // shadow cut off any closer draws the pager's edges as a faint box round the cover.
        val room = 48.dp
        val art = if (wide) minOf(maxHeight * 0.8f, maxHeight - room * 2, maxWidth * 0.4f, 480.dp) else minOf(maxWidth * 0.8f, maxHeight * 0.55f, 480.dp)
        // Now Playing's artwork pager: the cover follows a finger and skips where it's let go,
        // and slides itself when the track changes. Upright, it slides the full width; beside
        // the words, within its own square.
        val justBack = rememberJustBack()
        val cover: @Composable () -> Unit = {
            // Upright, out past the page's side margins to the screen's edges, so covers aren't cut off short of them.
            val edge = if (tv) 48.dp else 24.dp
            ArtworkPager(justBack, if (wide) Modifier.size(art + room * 2) else Modifier.requiredWidth(maxWidth + edge * 2).height(art + room * 2)) { c ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Cover(c, Modifier.size(art).shadow(24.dp, RoundedCornerShape(8.dp)), size = 900, shape = RoundedCornerShape(8.dp))
                }
            }
        }
        val words: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
            Column(horizontalAlignment = align) {
                val big = if (tv || wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                Text(cur.title, style = big.copy(shadow = glow), fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
                Text(cur.artist, style = MaterialTheme.typography.titleMedium.copy(shadow = glow), color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
                if (cur.album.isNotBlank()) {
                    Text(cur.album, style = MaterialTheme.typography.bodyMedium.copy(shadow = glow), color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
                }
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The cover's room is the gap: the words sit that far from it.
                cover()
                Box(Modifier.widthIn(max = 480.dp)) { words(Alignment.Start, TextAlign.Start) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                cover()
                words(Alignment.CenterHorizontally, TextAlign.Center)
            }
        }
    }
}

/** The lyrics, following along; nothing to scroll or nudge here. */
@Composable
private fun LyricsPage(player: PlayerConnection, modifier: Modifier) {
    val ui by player.ui.collectAsStateWithLifecycle()
    val cur = ui.current ?: return
    val lyrics = rememberLyrics(cur)
    Box(modifier, contentAlignment = Alignment.Center) {
        // Lyrics get the height; a "no lyrics" message just its own.
        val tall = if (lyrics is LyricsState.Ready) Modifier.fillMaxHeight() else Modifier.padding(vertical = 32.dp)
        LyricsPane(lyrics, cur, Modifier.widthIn(max = 640.dp).fillMaxWidth().then(tall)
            .padding(horizontal = 24.dp), interactive = false)
    }
}

/** What's playing and what's next, as many as fit: to look at, not to change. */
@Composable
private fun QueuePage(player: PlayerConnection, modifier: Modifier) {
    val ui by player.ui.collectAsStateWithLifecycle()
    val cur = ui.current ?: return
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Two headers and a row for now playing, then as many next as fit.
        val fit = ((maxHeight - 44.dp * 2 - 60.dp) / 60.dp).toInt().coerceIn(0, 12)
        val next = ui.queue.drop(ui.index + 1).take(fit)
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            QueueHeader(stringResource(R.string.vis_now_playing))
            QueueLine(cur, now = true)
            if (next.isNotEmpty()) {
                QueueHeader(
                    when {
                        next.first().user -> stringResource(R.string.vis_next_in_queue)
                        ui.contextName != null -> stringResource(R.string.vis_next_from, ui.contextName!!)
                        else -> stringResource(R.string.vis_next_from_queue)
                    },
                )
                for (e in next) QueueLine(e, now = false)
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall.copy(shadow = glow), fontWeight = FontWeight.Bold, color = Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.height(44.dp).padding(horizontal = 20.dp).wrapContentHeight(Alignment.CenterVertically))
}

@Composable
private fun QueueLine(e: QueueEntry, now: Boolean) {
    Row(Modifier.height(60.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(e.coverArt, Modifier.size(44.dp), size = 120, shape = RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (now) MaterialTheme.colorScheme.primary else Color.White, fontWeight = if (now) FontWeight.Bold else null)
            Text(e.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/**
 * A cover that slides to the next track's, like Now Playing's artwork: in
 * from the right for a track further on, from the left for one back, and
 * not at all between tracks with the same art. [gap] between the two on the
 * way; [clip] keeps the slide inside the cover's own square.
 */
@Composable
private fun SlidingCover(entry: QueueEntry, index: Int, modifier: Modifier, gap: Dp, clip: Boolean, cover: @Composable (QueueEntry) -> Unit) {
    val gapPx = with(LocalDensity.current) { gap.roundToPx() }
    val slide = tween<IntOffset>(ART_CHANGE_MS, easing = EaseOutCubic)
    AnimatedContent(
        Shown(entry, index), modifier.then(if (clip) Modifier.clipToBounds() else Modifier),
        contentKey = { artOf(it.entry) }, label = "cover",
        transitionSpec = {
            val sign = if (targetState.index >= initialState.index) 1 else -1
            (slideInHorizontally(slide) { sign * (it + gapPx) } + fadeIn(tween(400))) togetherWith
                (slideOutHorizontally(slide) { -sign * (it + gapPx) } + fadeOut(tween(ART_CHANGE_MS / 2, easing = EaseOutCubic)))
        },
    ) { cover(it.entry) }
}

/** What [SlidingCover] shows: the entry, and where it is in the queue (for the direction). */
private data class Shown(val entry: QueueEntry, val index: Int)

/** Cover, title, artist and "1:23 / 3:45", left aligned, while [visible]. */
@Composable
private fun TrackCard(player: PlayerConnection, visible: Boolean, tv: Boolean, modifier: Modifier) {
    val ui by player.ui.collectAsStateWithLifecycle()
    val cur = ui.current ?: return
    // Polled only while showing; the text changes once a second.
    var posMs by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(visible, cur.key) {
        while (visible) { posMs = player.positionMs; delay(250) }
    }
    val durationMs = ui.durationMs.takeIf { it > 0 } ?: (cur.duration * 1000L)
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SlidingCover(cur, ui.index, Modifier.size(if (tv) 80.dp else 60.dp), gap = 0.dp, clip = true) { e ->
                Cover(e.coverArt, Modifier.fillMaxSize().shadow(10.dp, RoundedCornerShape(6.dp)), size = 200, shape = RoundedCornerShape(6.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.widthIn(max = 420.dp)) {
                Text(cur.title, style = MaterialTheme.typography.titleMedium.copy(shadow = glow), fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(cur.artist, style = MaterialTheme.typography.bodyMedium.copy(shadow = glow),
                    color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${clock(posMs)} / ${clock(durationMs)}", style = MaterialTheme.typography.labelMedium.copy(shadow = glow),
                    color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * The preset on screen: a flag (hides it for good; Settings → Visualiser
 * brings it back), back and forward through presets either side of its name,
 * and a heart (loved presets come up more often). Chevrons, not the transport's
 * skip icons, so they don't read as changing the track.
 */
@Composable
private fun PresetBar(prefs: VisPrefs, path: String, onHide: () -> Unit, onStep: (forward: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val lovedList by prefs.loved.collectAsStateWithLifecycle()
    val loved = lovedList.any { it.path == path }
    val preset = VisPrefs.Preset(path)
    // The heart takes remote focus when the bar appears: the gentler of the two.
    val heartFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { heartFocus.requestFocus() } }
    val side = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Row(modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHide, Modifier.size(52.dp).focusRing(CircleShape, Color.White), colors = side) {
            GlowIcon(Icons.Outlined.Flag, stringResource(R.string.vis_hide_preset))
        }
        IconButton(onClick = { onStep(false) }, Modifier.size(52.dp).focusRing(CircleShape, Color.White), colors = side) {
            GlowIcon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.vis_previous_preset), size = 32.dp)
        }
        Column(Modifier.weight(1f, fill = false).widthIn(max = 520.dp).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(preset.name, style = MaterialTheme.typography.titleSmall.copy(shadow = glow), fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preset.folder.isNotEmpty()) {
                Text(preset.folder, style = MaterialTheme.typography.bodySmall.copy(shadow = glow),
                    color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { onStep(true) }, Modifier.size(52.dp).focusRing(CircleShape, Color.White), colors = side) {
            GlowIcon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.vis_next_preset), size = 32.dp)
        }
        IconButton(onClick = { prefs.setLoved(path, !loved) },
            Modifier.size(52.dp).focusRing(CircleShape, Color.White).focusRequester(heartFocus), colors = side) {
            GlowIcon(if (loved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, stringResource(if (loved) R.string.vis_unlove_preset else R.string.vis_love_preset),
                tint = if (loved) MaterialTheme.colorScheme.primary else Color.White)
        }
    }
}

/** The sign that flashes up in the middle on a play/pause or a skip, so the gesture shows. */
@Composable
private fun PulseSign(pulse: Pulse, modifier: Modifier) {
    var shown by remember(pulse) { mutableStateOf(true) }
    LaunchedEffect(pulse) { delay(PULSE_MS); shown = false }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.size(96.dp).panel(CircleShape), contentAlignment = Alignment.Center) {
            val icon = when (pulse.kind) {
                Pulse.Kind.PLAY -> Icons.Filled.PlayArrow
                Pulse.Kind.PAUSE -> Icons.Filled.Pause
                Pulse.Kind.NEXT -> Icons.Filled.SkipNext
                Pulse.Kind.PREVIOUS -> Icons.Filled.SkipPrevious
            }
            Icon(icon, null, Modifier.size(52.dp), tint = Color.White)
        }
    }
}

/** A preview's caption: the preset's name and where it sits in the pack. */
@Composable
private fun PresetLabel(path: String, modifier: Modifier) {
    val preset = VisPrefs.Preset(path)
    Column(modifier.widthIn(max = 640.dp)) {
        Text(stringResource(R.string.vis_preview), style = MaterialTheme.typography.labelMedium.copy(shadow = glow), color = Color.White.copy(alpha = 0.7f))
        Text(preset.name, style = MaterialTheme.typography.titleMedium.copy(shadow = glow), fontWeight = FontWeight.Bold,
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (preset.folder.isNotEmpty()) {
            Text(preset.folder, style = MaterialTheme.typography.bodyMedium.copy(shadow = glow), color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** 3:45, or 1:02:03 past an hour. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
