package eu.bradshaw.seidr.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.connect.Peer
import eu.bradshaw.seidr.data.Listener
import eu.bradshaw.seidr.data.ListeningRules
import eu.bradshaw.seidr.data.Output
import eu.bradshaw.seidr.ui.LocalActions
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.highlightClickable
import eu.bradshaw.seidr.ui.player.kindIcon
import kotlinx.coroutines.delay

/** Everyone else listening now (see `Listening`), polled while this is on screen. */
@Composable
fun rememberListeners(): List<Listener> {
    val all by LocalActions.current.graph.listening.others.collectAsStateWithLifecycle()
    return all
}

/** Three bars that bounce while anyone in the group plays, and rest when all are paused. */
@Composable
fun ListeningBars(playing: Boolean, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val color = MaterialTheme.colorScheme.primary
    val bounce = rememberInfiniteTransition(label = "bars")
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0, 180, 90).forEachIndexed { i, offset ->
            val level = if (playing) {
                bounce.animateFloat(
                    0.3f, 1f,
                    infiniteRepeatable(tween(420 + i * 70, delayMillis = offset), RepeatMode.Reverse),
                    label = "bar$i",
                ).value
            } else listOf(0.5f, 0.8f, 0.35f)[i]
            Box(Modifier.width(3.dp).fillMaxHeight(level).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

/** "Sam", "Sam and Alex", "Sam +2": each person once, however many devices they're on. */
@Composable
fun listenerNames(listeners: List<Listener>): String {
    val names = listeners.map { it.user }.distinctBy { it.lowercase() }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> stringResource(R.string.listening_two, names[0], names[1])
        else -> stringResource(R.string.listening_more, names[0], names.size - 1)
    }
}

/** A Home card's second line: who, and "paused" when that's all they are. */
@Composable
fun listeningLine(listeners: List<Listener>): String {
    val names = listenerNames(listeners)
    return if (listeners.all { it.paused }) stringResource(R.string.listening_paused_who, names) else names
}

/**
 * Under a playlist's or album's title, while anyone else is playing from it:
 * who, and what. A tap shows everything (`ListeningDialog`).
 */
@Composable
fun ListeningStrip(listeners: List<Listener>) {
    if (listeners.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val first = listeners.first()
    val label = stringResource(R.string.home_listening)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRing(shape),
    ) {
        Row(
            Modifier.highlightClickable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListeningBars(listeners.any { !it.paused }, height = 16.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listeningLine(listeners), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                first.song?.let { s ->
                    Text(listOf(s.title, s.artistLine).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) ListeningDialog(listeners) { open = false }
}

/** Who's on this song row: their initials, beside the menu. */
@Composable
fun ListenerBadges(listeners: List<Listener>) {
    val users = listeners.map { it.user }.distinctBy { it.lowercase() }
    if (users.isEmpty()) return
    val who = listenerNames(listeners)
    Row(Modifier.padding(start = 6.dp).clearAndSetSemantics { contentDescription = who }, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        for (u in users.take(2)) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(u.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** Everything about what each of [listeners] is playing: the song (artist and album open), their device and where it plays. */
@Composable
fun ListeningDialog(listeners: List<Listener>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val playlists by actions.graph.browse.playlists.collectAsStateWithLifecycle()
    fun go(route: Route) { onDismiss(); actions.open(route) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { ListeningBars(listeners.any { !it.paused }, height = 20.dp) },
        title = { Text(stringResource(R.string.home_listening)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listeners.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider()
                    ListenerDetails(l, now, playlists.orEmpty().find { it.id == l.playlistId }?.name, ::go)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun ListenerDetails(l: Listener, now: Long, playlistName: String?, go: (Route) -> Unit) {
    val s = l.song
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(l.user, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stateText(l, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s != null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(s.coverArt, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), size = 120)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val artistId = s.artistId ?: s.artists.singleOrNull()?.id
                    LinkLine(s.artistLine, Icons.Filled.Person, artistId?.let { { go(Route.Artist(it)) } })
                    LinkLine(s.album.orEmpty(), Icons.Filled.Album, s.albumId?.let { { go(Route.Album(it)) } })
                }
            }
        }
        if (playlistName != null && l.playlistId != null) {
            LinkLine(stringResource(R.string.listening_from, playlistName), Icons.AutoMirrored.Filled.QueueMusic, { go(Route.Playlist(l.playlistId)) })
        }
        DeviceLine(l)
    }
}

/** "Playing · 3:12 of 7:19", "Playing" (another app says no more), or "Paused". */
@Composable
private fun stateText(l: Listener, now: Long): String {
    if (l.paused) return stringResource(R.string.listening_paused)
    val at = l.positionAt(now)
    val length = l.song?.duration ?: 0
    if (at == null || length <= 0) return stringResource(R.string.listening_playing)
    val secs = (at / 1000).toInt().coerceIn(0, length)
    return stringResource(R.string.listening_playing_at, formatDuration(secs), formatDuration(length))
}

/** A line that opens something when [onClick] is given. */
@Composable
private fun LinkLine(text: String, icon: ImageVector, onClick: (() -> Unit)?) {
    if (text.isBlank()) return
    val m = if (onClick != null) Modifier.highlightClickable(onClick = onClick) else Modifier
    Row(m.padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "📱 Galaxy Fold", then "↳ 🔊 Kitchen speaker" when it plays elsewhere; another app is just its name. */
@Composable
private fun DeviceLine(l: Listener) {
    val device = if (l.isApp && l.device.equals(ListeningRules.CLIENT, ignoreCase = true)) stringResource(R.string.app_name) else l.device
    val out = l.output?.let { outputLabel(it) }
    val said = if (out == null) device else stringResource(R.string.listening_through, device, out)
    // The output on a line of its own, under the device: a speaker's name can be long.
    Column(Modifier.padding(top = 4.dp).clearAndSetSemantics { contentDescription = said }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (l.isApp) Icons.Filled.Apps else kindIcon(l.deviceKind!!), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(device, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (out != null && l.output != null) Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SubdirectoryArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Icon(outputIcon(l.output), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(out, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun outputLabel(o: Output): String? = when (o.kind) {
    ListeningRules.OUT_HEADPHONES -> stringResource(R.string.listening_output_headphones)
    ListeningRules.OUT_BLUETOOTH -> stringResource(R.string.listening_output_bluetooth)
    ListeningRules.OUT_CAR -> stringResource(R.string.listening_output_car)
    else -> o.name?.takeIf { it.isNotBlank() }
}

private fun outputIcon(o: Output): ImageVector = when (o.kind) {
    ListeningRules.OUT_HEADPHONES -> Icons.Filled.Headphones
    ListeningRules.OUT_BLUETOOTH -> Icons.Filled.Bluetooth
    ListeningRules.OUT_CAR -> Icons.Filled.DirectionsCar
    else -> kindIcon(o.kind.takeIf { it in CAST_KINDS } ?: Peer.KIND_SPEAKER)
}

private val CAST_KINDS = setOf(Peer.KIND_SPEAKER, Peer.KIND_SPEAKER_GROUP, Peer.KIND_TV)
