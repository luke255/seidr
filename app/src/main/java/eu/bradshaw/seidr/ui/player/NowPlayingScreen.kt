package eu.bradshaw.seidr.ui.player

import androidx.compose.ui.res.stringResource
import eu.bradshaw.seidr.R
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import eu.bradshaw.seidr.ui.components.ReportItems
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.ViewInAr
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
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
import eu.bradshaw.seidr.ui.artGradient
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import eu.bradshaw.seidr.vis.VisualiserActivity
import eu.bradshaw.seidr.vis.VisWarning
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import eu.bradshaw.seidr.ui.primaryFocus
import eu.bradshaw.seidr.ui.LocalIsTv
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager

fun QueueEntry.asSong() = Song(
    id = songId, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId,
    coverArt = coverArt, duration = duration,
)

/**
 * Full Now Playing: big swipeable artwork, title/artist with a heart, scrubber,
 * transport controls and a queue button. Always full screen; on wide screens
 * the controls keep a phone-like width in the middle.
 */
@Composable
fun NowPlayingScreen(onClose: (() -> Unit)?, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    // Colours follow the current track's art (not under Neutral). Monochrome
    // art, or none, goes grey rather than back to the app's theme colour.
    val ui by LocalActions.current.player.ui.collectAsStateWithLifecycle()
    val colourful = LocalColourful.current
    val base = MaterialTheme.colorScheme
    val art = rememberArtColours(if (colourful) ui.current?.coverArt else null)
    val target = remember(art, base) { art?.let(::artScheme) ?: if (colourful) monochromeArtScheme else ArtScheme(base, base.primaryContainer) }
    val justBack = rememberJustBack()
    // Stepped for the theme, smooth for the background: see SchemeFade.
    val fade = rememberSchemeFade(target.scheme, target.wash, artFadeProgress(justBack()))
    val scheme = fade.scheme
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            NowPlayingContent(onClose, onQueue, modifier, fade = if (colourful) fade else null, justBack = justBack)
        }
    }
}

/**
 * [fade]: the colours, for the wash fading down from the top; null for Neutral's quieter look.
 * A clearly landscape window (TV, a phone on its side) puts the artwork on the
 * left at full height with the controls beside it; otherwise it's stacked.
 */
@Composable
private fun NowPlayingContent(onClose: (() -> Unit)?, onQueue: () -> Unit, modifier: Modifier, fade: SchemeFade?, justBack: () -> Boolean) {
    val actions = LocalActions.current
    val player = actions.player
    val ui by player.ui.collectAsStateWithLifecycle()
    val cur = ui.current
    // Seiðr Connect: the device playing, when it isn't this one.
    val remote by actions.graph.connect.active.collectAsStateWithLifecycle()
    // Signed out, this device only plays what another one sent: no Connect of its own,
    // a way to end that instead, and nowhere else to go.
    val signedIn by actions.graph.settings.server.collectAsStateWithLifecycle()
    val guest by actions.graph.guest.collectAsStateWithLifecycle()
    val asGuest = signedIn == null
    var devicesOpen by remember { mutableStateOf(false) }
    if (devicesOpen) DevicesDialog(onDismiss = { devicesOpen = false })
    val devicesButton: @Composable () -> Unit = {
        if (asGuest) {
            IconButton(onClick = { actions.graph.connect.disconnectGuest() }) {
                Icon(Icons.Filled.LinkOff, stringResource(R.string.player_disconnect), tint = MaterialTheme.colorScheme.primary)
            }
        } else IconButton(onClick = { devicesOpen = true }) {
            Icon(Icons.Filled.Devices, stringResource(R.string.player_play_on_another_device),
                tint = if (remote != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    }

    val pc = MaterialTheme.colorScheme.primaryContainer
    val plainBg = MaterialTheme.colorScheme.background
    // Drawn every frame of a fade from the fade itself, which recomposes nothing.
    val background = if (fade != null) Modifier.drawBehind {
        val wash = fade.wash()
        val under = fade.background()
        drawRect(artGradient(wash, under))
    } else Modifier.background(plainBg).background(Brush.verticalGradient(listOf(pc.copy(alpha = 0.55f), plainBg)))

    // Header: close, "Playing from", overflow.
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.common_close)) }
            else Spacer(Modifier.size(48.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.player_playing_from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.contextName ?: stringResource(R.string.player_queue), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            var menu by remember { mutableStateOf(false) }
            if (asGuest) Spacer(Modifier.size(48.dp))
            else Box {
                IconButton(onClick = { menu = true }, enabled = cur != null) { Icon(Icons.Filled.MoreVert, stringResource(R.string.common_more)) }
                if (cur != null) TrackMenu(cur.asSong(), menu, { menu = false })
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val sideBySide = maxWidth > maxHeight * 1.25f
        val artSide = minOf(maxHeight - 16.dp, maxWidth * 0.55f)
        if (cur == null) {
            Column(Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                header()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.player_nothing_playing))
                        devicesButton()
                    }
                }
            }
            return@BoxWithConstraints
        }

        // Lyrics take the artwork's place while they're open.
        val lyrics = rememberLyrics(cur)
        var showLyrics by rememberSaveable { mutableStateOf(false) }
        val artwork: @Composable (Modifier) -> Unit = { m ->
            if (showLyrics) {
                LyricsPane(lyrics, cur, m)
            } else {
                // Artwork pager: swipe left/right to skip. Inside the if so that coming
                // back from lyrics starts a fresh pager on the current track.
                ArtworkPager(justBack, m) { cover ->
                    // aspectRatio without a fixed width shrinks to fit the height on short screens.
                    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Cover(cover, Modifier.aspectRatio(1f), size = 900)
                    }
                }
            }
        }
        val controls: @Composable () -> Unit = {
            // Title and artist. Like and report are in the ⋮ menu.
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cur.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(cur.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }

            Scrubber(cur)

            Transport()
            // Devices, lyrics, visualiser, queue: each centred in its quarter of the width.
            // On a Quest, the immersive visualiser sits beside the flat one.
            val context = LocalContext.current
            val headset = remember { XrVisualiserActivity.available(context) }
            val pill = remote != null || (asGuest && guest != null)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = if (pill) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { devicesButton() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { showLyrics = !showLyrics }, enabled = showLyrics || lyrics != LyricsState.None) {
                        Icon(Icons.Filled.Lyrics, stringResource(if (showLyrics) R.string.player_hide_lyrics else R.string.player_lyrics),
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row {
                        // It visualises what this device plays: nothing, while another one is playing.
                        // The first time for each, a flashing-lights warning.
                        val prefs = actions.graph.visPrefs
                        var warn by remember { mutableStateOf<Boolean?>(null) } // immersive, or not
                        val open = { immersive: Boolean ->
                            context.startActivity(Intent(context, if (immersive) XrVisualiserActivity::class.java else VisualiserActivity::class.java))
                        }
                        warn?.let { immersive ->
                            VisWarning(immersive, onCancel = { warn = null }, onContinue = {
                                warn = null
                                prefs.setWarned(immersive)
                                open(immersive)
                            })
                        }
                        val visualise = { immersive: Boolean -> if (prefs.warned(immersive)) open(immersive) else warn = immersive }
                        IconButton(onClick = { visualise(false) }, enabled = remote == null) {
                            Icon(Icons.Filled.GraphicEq, stringResource(R.string.player_visualiser))
                        }
                        if (headset) {
                            IconButton(onClick = { visualise(true) }, enabled = remote == null) {
                                Icon(Icons.Filled.ViewInAr, stringResource(R.string.player_immersive_visualiser))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.player_queue)) }
                }
            }
            remote?.let { r ->
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.clip(RoundedCornerShape(50)).clickable { devicesOpen = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(r.kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.player_playing_on, r.name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (asGuest) guest?.let { g ->
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.player_playing_for, g.config.username, g.from), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        if (sideBySide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // As tall as the window allows, but leaving the controls at least ~45%. A fixed
                // size: fillMaxHeight + aspectRatio overflows its width cap on a squarish window
                // (the Fold's inner screen), running the cover under the controls.
                artwork(Modifier.size(artSide))
                Column(Modifier.weight(1f).widthIn(max = 480.dp).fillMaxHeight()) {
                    header()
                    Spacer(Modifier.weight(1f))
                    controls()
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            Column(
                Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header()
                val pane = Modifier.weight(1f).fillMaxWidth().widthIn(max = 520.dp)
                artwork(if (showLyrics) pane.padding(top = 8.dp) else pane)
                controls()
            }
        }
    }
}

/** Previous, play/pause and next, as on Now Playing (also the visualiser's transport page). */
@Composable
internal fun Transport() {
    val player = LocalActions.current.player
    val ui by player.ui.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val prevFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var nextFocused by remember { mutableStateOf(false) }
    // Shuffle and repeat live at the top of the Queue screen.
    // Previous and next are the two halves of a pill, split by a circle around
    // play/pause (its radius plus a gap); each whole half is the button. Play/pause is
    // drawn last so it wins taps where their bounds overlap.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.width(SkipHalfWidth * 2), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            // Play/pause overlaps both halves, so the D-pad can't find it (or them)
            // sideways by geometry: link the three explicitly.
            SkipHalf(start = true, onClick = player::previous,
                Modifier.focusRequester(prevFocus).focusProperties { end = playFocus }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(36.dp)) }
            // Hidden at the end of the queue (its space kept, so the row doesn't shift). If
            // it had remote focus, focus moves to play/pause rather than being lost.
            if (ui.hasNext) {
                SkipHalf(start = false, onClick = player::next, Modifier.focusRequester(nextFocus)
                    .focusProperties { start = playFocus }.onFocusChanged { nextFocused = it.hasFocus }) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next), Modifier.size(36.dp))
                }
            } else {
                Spacer(Modifier.size(SkipHalfWidth, SkipHalfHeight))
                LaunchedEffect(Unit) { if (nextFocused) { nextFocused = false; playFocus.requestFocus() } }
            }
        }
        FilledIconButton(onClick = player::playPause, Modifier.size(72.dp).focusRing(CircleShape).primaryFocus().focusRequester(playFocus)
            .focusProperties { start = prevFocus; end = if (ui.hasNext) nextFocus else FocusRequester.Default }, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors()) {
            Icon(if (ui.showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(if (ui.showPause) R.string.player_pause else R.string.player_play), Modifier.size(40.dp))
        }
    }
}

@Composable
internal fun Scrubber(cur: QueueEntry) {
    val player = LocalActions.current.player
    val ui by player.ui.collectAsStateWithLifecycle()
    var pos by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = (ui.durationMs.takeIf { it > 0 } ?: cur.duration * 1000L).toFloat().coerceAtLeast(1f)
    // Every frame while playing, on the frame clock (see SongClock), so the bar glides
    // rather than stepping. Polls while paused too, so seeks from elsewhere (a lyrics
    // line tap) show up.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cur.key, ui.isPlaying) {
        val playing = ui.isPlaying
        val clock = SongClock()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = if (playing) withFrameNanos { clock.at(it, player.positionMs) } else player.positionMs.toDouble()
                if (dragging == null) pos = now.toFloat()
                if (!playing) delay(250)
            }
        }
    }
    // pos changes every frame: only the slider reads it directly, and the times
    // recompose once a second.
    val elapsed by remember { derivedStateOf { ((dragging ?: pos) / 1000).toInt() } }
    val remaining by remember(dur) { derivedStateOf { ((dur - (dragging ?: pos)) / 1000).toInt() } }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // The bar has no visible focus state of its own; use the same layer the ripple's focus state does.
    val focusLayer = LocalContentColor.current.copy(alpha = 0.3f)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        WavyScrubber(
            fraction = { ((dragging ?: pos) / dur).coerceIn(0f, 1f) },
            playing = ui.isPlaying,
            // Remote: left/right skip 10 seconds; up/down move focus on.
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) focusLayer else Color.Transparent, RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { e ->
                    val step = when (e.key) {
                        Key.DirectionRight -> 10_000f
                        Key.DirectionLeft -> -10_000f
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (e.type == KeyEventType.KeyDown) {
                                focusManager.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                            }
                            return@onPreviewKeyEvent true
                        }
                        else -> return@onPreviewKeyEvent false
                    }
                    if (e.type == KeyEventType.KeyDown) {
                        val to = (pos + step).coerceIn(0f, dur)
                        player.seekTo(to.toLong()); pos = to
                    }
                    true
                },
            onValueChange = { dragging = it * dur },
            onValueChangeFinished = { dragging?.let { player.seekTo(it.toLong()); pos = it }; dragging = null },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(elapsed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration(remaining), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}



// The transport's skip buttons: each is half of a 236×52dp pill, cut by a circle 6dp
// outside the 72dp play/pause button. Icons sit 24dp in from the outer end, a little
// closer to play/pause than the middle of the half.
private val SkipHalfWidth = 118.dp
private val SkipHalfHeight = 52.dp
private val SkipCut = (36 + 6).dp
private val SkipIconInset = 24.dp

@Composable
private fun SkipHalf(start: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    val shape = remember(start) { HalfPill(start, SkipCut) }
    // focusRing draws its shape 4.5dp (gap + half the stroke) outside the button.
    val ring = remember(start) { HalfPill(start, SkipCut, inset = 4.5.dp) }
    Box(modifier.size(SkipHalfWidth, SkipHalfHeight).focusRing(ring).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(start = if (start) SkipIconInset else 0.dp, end = if (start) 0.dp else SkipIconInset),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd) { icon() }
}

/**
 * Half a pill: the outer end rounded, the inner end cut away by a circle of radius [cut]
 * centred on it. [inset] is for an outline drawn that far outside the shape (a focus ring),
 * which is laid out [inset] larger on every side, so the cut shrinks to follow it.
 */
private class HalfPill(private val start: Boolean, private val cut: Dp, private val inset: Dp = 0.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = start == (layoutDirection == LayoutDirection.Ltr)
        val i = with(density) { inset.toPx() }
        val r = with(density) { cut.toPx() } - i
        val round = CornerRadius(size.height / 2)
        val pill = Path().apply {
            addRoundRect(if (left) RoundRect(0f, 0f, size.width, size.height, round, CornerRadius.Zero, CornerRadius.Zero, round)
                else RoundRect(0f, 0f, size.width, size.height, CornerRadius.Zero, round, round, CornerRadius.Zero))
        }
        val circle = Path().apply { addOval(Rect(androidx.compose.ui.geometry.Offset(if (left) size.width - i else i, size.height / 2), r)) }
        return Outline.Generic(Path.combine(PathOperation.Difference, pill, circle))
    }
}