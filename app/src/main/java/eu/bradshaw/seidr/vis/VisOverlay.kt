package eu.bradshaw.seidr.vis

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.playback.PlayerConnection
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.components.Cover
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.ui.theme.SeidrTheme
import eu.bradshaw.seidr.ui.theme.ThemeChoice
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Keeps white text legible over whatever a preset draws. */
private val glow = Shadow(Color.Black, offset = Offset(0f, 2f), blurRadius = 10f)

/** How long a play/pause or skip sign stays up. */
private const val PULSE_MS = 1_200L

/** A translucent dark panel, for controls. */
private fun Modifier.panel(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp)) =
    background(Color.Black.copy(alpha = 0.45f), shape)

/**
 * Everything over the visualiser: the track card (bottom left); with
 * [controls], the [preset] on screen with a flag and a heart (at the top); a
 * passing [message]; a play/pause or skip [pulse]; and the touch gestures
 * (see [VisualiserActivity]). With [preview] (a preset's path), just its name.
 * Off TV, a close button shows with the controls (and in a preview): hand
 * tracking on a Quest has no Back.
 */
@Composable
fun VisOverlay(
    graph: Graph,
    preview: String?,
    preset: String?,
    controls: Boolean,
    message: String?,
    onControls: (Boolean) -> Unit,
    onHidePreset: () -> Unit,
    onMessageShown: () -> Unit,
    pulse: Pulse?,
    onPlayPause: () -> Unit,
    onSkip: (next: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val theme by graph.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tv = remember { isTv(context) }
    val player = graph.player
    CompositionLocalProvider(LocalIsTv provides tv) {
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
                    // A tap plays/pauses (or, with the controls up, puts them away); a hold shows them.
                    .pointerInput(preview, controls) {
                        if (preview != null) return@pointerInput
                        detectTapGestures(
                            onTap = { if (controls) onControls(false) else onPlayPause() },
                            onLongPress = { onControls(true) },
                        )
                    }
                    // Sideways skips (left for the next, like the cover in Now Playing); up or down
                    // shows the controls, and down puts them away again.
                    .pointerInput(preview, controls) {
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
                                    if (dx < -swipe) onSkip(true) else if (dx > swipe) onSkip(false)
                                } else if (abs(dy) > swipe) {
                                    onControls(!(controls && dy > 0))
                                }
                            },
                        ) { change, delta -> change.consume(); dx += delta.x; dy += delta.y }
                    },
            ) {
                if (pulse != null) PulseSign(pulse, Modifier.align(Alignment.Center))
                val edge = if (tv) PaddingValues(horizontal = 48.dp, vertical = 32.dp) else PaddingValues(24.dp)
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(edge)) {
                    if (preview != null) {
                        PresetLabel(preview, Modifier.align(Alignment.BottomStart))
                    } else {
                        val showInfo by graph.visPrefs.showInfo.collectAsStateWithLifecycle()
                        TrackCard(player, visible = showInfo || controls, tv, Modifier.align(Alignment.BottomStart))
                        AnimatedVisibility(controls && preset != null, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                            if (preset != null) PresetBar(graph.visPrefs, preset, onHidePreset)
                        }
                    }
                    if (!tv) {
                        AnimatedVisibility(controls || preview != null, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                            IconButton(onClick = onClose, Modifier.size(52.dp).panel(CircleShape),
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                                Icon(Icons.Filled.Close, "Close visualiser")
                            }
                        }
                    }
                    if (message != null) {
                        Text(
                            message, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                            // Under the preset bar, if it's showing.
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 88.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                        LaunchedEffect(message) { delay(3_500); onMessageShown() }
                    }
                }
            }
        }
    }
}

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
            Cover(cur.coverArt, Modifier.size(if (tv) 80.dp else 60.dp).shadow(10.dp, RoundedCornerShape(6.dp)), size = 200,
                shape = RoundedCornerShape(6.dp))
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
 * brings it back), its name, and a heart (loved presets come up more often).
 */
@Composable
private fun PresetBar(prefs: VisPrefs, path: String, onHide: () -> Unit) {
    val lovedList by prefs.loved.collectAsStateWithLifecycle()
    val loved = lovedList.any { it.path == path }
    val preset = VisPrefs.Preset(path)
    // The heart takes remote focus when the bar appears: the gentler of the two.
    val heartFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { heartFocus.requestFocus() } }
    val side = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Row(Modifier.panel(RoundedCornerShape(50)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHide, Modifier.size(52.dp).focusRing(CircleShape, Color.White), colors = side) {
            Icon(Icons.Outlined.Flag, "Hide preset")
        }
        Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(preset.name, style = MaterialTheme.typography.titleSmall.copy(shadow = glow), fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preset.folder.isNotEmpty()) {
                Text(preset.folder, style = MaterialTheme.typography.bodySmall.copy(shadow = glow),
                    color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { prefs.setLoved(path, !loved) },
            Modifier.size(52.dp).focusRing(CircleShape, Color.White).focusRequester(heartFocus), colors = side) {
            Icon(if (loved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (loved) "Unlove preset" else "Love preset",
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
        Text("Preview", style = MaterialTheme.typography.labelMedium.copy(shadow = glow), color = Color.White.copy(alpha = 0.7f))
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
