package eu.bradshaw.seidr.ui.components

import eu.bradshaw.seidr.ui.ArtWash
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import eu.bradshaw.seidr.ui.LocalIsTv
import eu.bradshaw.seidr.ui.focusRing
import eu.bradshaw.seidr.ui.primaryFocus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.data.db.OfflineSource
import eu.bradshaw.seidr.ui.LocalActions

/** Top bar with a back arrow; the title fades in once the header has scrolled away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    showTitle: Boolean = true,
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalActions.current.nav
    TopAppBar(
        title = { if (showTitle) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        navigationIcon = {
            if (showBack) IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

/**
 * The round buttons under every collection header: Shuffle Play, plus Play
 * (in order, shuffle off) when [onPlay] is given. The primary one is filled,
 * sits on the right and takes a remote's focus: Play for albums, Shuffle
 * ([shufflePrimary]) for playlists. Either one opens Now Playing.
 */
@Composable
fun PlayButtons(onShuffle: () -> Unit, enabled: Boolean = true, onPlay: (() -> Unit)? = null, shufflePrimary: Boolean = false) {
    val actions = LocalActions.current
    val shuffleThenOpen = { onShuffle(); actions.nowPlayingOpen = true }
    val playThenOpen = onPlay?.let { play -> { play(); actions.nowPlayingOpen = true } }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        val shuffle: @Composable (Boolean) -> Unit = { primary -> RoundPlayButton(shuffleThenOpen, enabled, primary) { Icon(Icons.Filled.Shuffle, "Shuffle play", Modifier.size(28.dp)) } }
        when {
            playThenOpen == null -> shuffle(true)
            shufflePrimary -> {
                RoundPlayButton(playThenOpen, enabled, primary = false) { Icon(Icons.Filled.PlayArrow, "Play in order", Modifier.size(32.dp)) }
                shuffle(true)
            }
            else -> {
                shuffle(false)
                RoundPlayButton(playThenOpen, enabled, primary = true) { Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(32.dp)) }
            }
        }
    }
}

@Composable
private fun RoundPlayButton(onClick: () -> Unit, enabled: Boolean, primary: Boolean, icon: @Composable () -> Unit) {
    val m = Modifier.size(56.dp).focusRing(CircleShape)
    if (primary) FilledIconButton(onClick = onClick, enabled = enabled, modifier = m.primaryFocus()) { icon() }
    else FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = m) { icon() }
}

/**
 * A header's facts line ("by x · 12 songs · 48 min"), ending in the
 * downloaded icon when every track is on the phone.
 */
@Composable
fun HeaderFacts(text: String, downloaded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (downloaded) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ArrowCircleDown, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The classic Download switch. On creates an offline source and syncs it
 * straight away; off removes the source (files nothing else claims go).
 */
@Composable
fun DownloadToggle(source: OfflineSource, totalTracks: Int) {
    val actions = LocalActions.current
    val graph = actions.graph
    val sources by graph.downloads.sources.collectAsStateWithLifecycle()
    val usage by graph.downloads.usage.collectAsStateWithLifecycle()
    val enabled = source.id in sources
    val u = usage[source.id]
    val label = when {
        !enabled -> "Download"
        u == null || u.tracks == 0 -> "Waiting to download…"
        u.done < u.tracks -> "Downloading… ${u.done} of ${u.tracks}"
        else -> "Downloaded"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Switch(checked = enabled, onCheckedChange = { on ->
            graph.sync.setOffline(source, on)
            if (on && !graph.status.online.value) actions.toast("Will download when the server is reachable")
            if (!on) actions.toast("Removed downloads for ${source.name}")
        })
    }
}

/**
 * Shared layout for playlist, album and Liked Songs: art, title, subtitle,
 * play buttons, Download switch, then rows. Stacked and centred on the cover
 * screen; art beside the text on wide panes, so tracks start on screen. The
 * screen's wash takes its colour from [washCover].
 */
@Composable
fun CollectionLayout(
    title: String,
    art: @Composable (Modifier) -> Unit,
    subtitle: @Composable () -> Unit,
    onShuffle: () -> Unit,
    shuffleEnabled: Boolean,
    onPlay: (() -> Unit)? = null,
    shufflePrimary: Boolean = false,
    offline: OfflineSource?,
    trackCount: Int,
    topActions: @Composable RowScope.() -> Unit = {},
    headerExtra: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    washCover: String? = null,
    rows: LazyListScope.() -> Unit,
) {
    ArtWash(washCover)
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val tv = LocalIsTv.current
    val wide = LocalWideShell.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title, showTitle = showTitle, actions = topActions)
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Short windows (a TV pane) shrink the art so the play buttons fit under it unscrolled.
        val artMax = (maxHeight - 200.dp).coerceIn(120.dp, 220.dp)
        val wideArt = (maxWidth * 0.32f).coerceIn(120.dp, 200.dp)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = bottomContentPadding(24.dp)) {
            item(key = "header") {
                val header = Modifier
                    .fillMaxWidth()
                    // Focus coming back up into the header (from the tracks) shows all of it, art included.
                    .onFocusChanged {
                        if (it.hasFocus && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 0) {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }
                    .padding(horizontal = 16.dp)
                val artShape = RoundedCornerShape(8.dp)
                if (wide) {
                    Row(header.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.size(wideArt).shadow(12.dp, artShape).clip(artShape)) {
                            art(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            subtitle()
                            headerExtra()
                            Spacer(Modifier.height(6.dp))
                            PlayButtons(onShuffle, shuffleEnabled, onPlay, shufflePrimary)
                        }
                    }
                } else {
                    Column(
                        header,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.widthIn(max = artMax).fillMaxWidth(0.6f).shadow(12.dp, artShape).clip(artShape)) { art(Modifier.fillMaxWidth()) }
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        subtitle()
                        headerExtra()
                        Spacer(Modifier.height(4.dp))
                        PlayButtons(onShuffle, shuffleEnabled, onPlay, shufflePrimary)
                    }
                }
            }
            // No downloads on TV: it streams.
            if (offline != null && !tv) item(key = "download") { DownloadToggle(offline, trackCount) }
            rows()
        }
        }
    }
}
