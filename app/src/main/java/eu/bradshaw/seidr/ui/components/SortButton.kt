package eu.bradshaw.seidr.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.AlbumSort
import eu.bradshaw.seidr.data.ArtistSort
import eu.bradshaw.seidr.data.PlaylistSort
import eu.bradshaw.seidr.data.TrackSort

/** [SortButton] over an enum's [entries], named by [label]. */
@Composable
fun <T : Enum<T>> SortButton(entries: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SortButton(entries.map { stringResource(label(it)) }, entries.indexOf(selected), { onSelect(entries[it]) }, modifier)
}

fun PlaylistSort.label(): Int = when (this) {
    PlaylistSort.RECENT -> R.string.home_sort_recent
    PlaylistSort.NAME -> R.string.library_sort_name
    PlaylistSort.UPDATED -> R.string.library_sort_updated
    PlaylistSort.CUSTOM -> R.string.library_sort_custom
}

fun AlbumSort.label(): Int = when (this) {
    AlbumSort.NAME -> R.string.library_sort_name
    AlbumSort.ARTIST -> R.string.library_sort_artist
    AlbumSort.ADDED -> R.string.library_sort_added
    AlbumSort.YEAR -> R.string.library_sort_year
    AlbumSort.PLAYS -> R.string.library_sort_most_played
}

fun ArtistSort.label(): Int = when (this) {
    ArtistSort.NAME -> R.string.library_sort_name
    ArtistSort.ALBUMS -> R.string.library_sort_most_albums
    ArtistSort.PLAYS -> R.string.library_sort_most_played
}

fun TrackSort.label(): Int = when (this) {
    TrackSort.CUSTOM -> R.string.library_sort_custom
    TrackSort.TITLE -> R.string.playlist_sort_title
    TrackSort.ARTIST -> R.string.library_sort_artist
    TrackSort.ALBUM -> R.string.playlist_sort_album
    TrackSort.YEAR -> R.string.library_sort_year
    TrackSort.PLAYS -> R.string.library_sort_most_played
}

/** The sort icon and the current order's name; tapped, a menu of [options] with a tick on [selected]. */
@Composable
fun SortButton(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(" " + options[selected], color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, s ->
                DropdownMenuItem({ Text(s) }, onClick = { onSelect(i); open = false },
                    trailingIcon = if (i == selected) { { Icon(Icons.Filled.Check, null) } } else null)
            }
        }
    }
}
