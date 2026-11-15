package eu.bradshaw.seidr.playback

import eu.bradshaw.seidr.data.AlbumPlays
import eu.bradshaw.seidr.data.KidRules
import java.io.File

/**
 * Where playback enforces kid mode, as pure decisions (null rules: not a
 * kid's login). Kept apart from the Android classes so the tests can reach them.
 */
object KidGate {
    sealed interface Source {
        data class Refused(val why: String) : Source
        data class Local(val file: File) : Source
        data object Stream : Source
    }

    /**
     * What the player's data source does with `seidr://song/<id>`. Kid mode's
     * last line: whatever route a song came by, it plays only if approved.
     * Then local files first, even online; offline fails fast.
     */
    fun source(songId: String, kid: KidRules?, local: File?, online: Boolean): Source = when {
        kid?.playable(songId) == false -> Source.Refused("Not approved")
        local != null -> Source.Local(local)
        !online -> Source.Refused("Offline and not downloaded")
        else -> Source.Stream
    }

    /**
     * A queue from the car or a voice search: only approved songs for a kid,
     * only downloaded ones offline. [first] is dropped if it doesn't survive.
     */
    fun queue(
        all: List<QueueEntry>, first: QueueEntry?, kid: KidRules?, online: Boolean, downloaded: (String) -> Boolean,
    ): Pair<List<QueueEntry>, QueueEntry?> {
        var list = all
        if (kid != null) list = list.filter { kid.playable(it.songId) }
        if (!online) list = list.filter { downloaded(it.songId) }
        return list to first?.takeIf { it in list }
    }

    /** Recently played albums for the car: only those a kid can see, and art only where allowed. */
    fun recentAlbums(plays: List<AlbumPlays.Play>, kid: KidRules?): List<AlbumPlays.Play> =
        if (kid == null) plays
        else plays.filter { kid.albumShows(it.id) }.map { it.copy(coverArt = it.coverArt.takeIf { _ -> kid.albumArtShows(it.id) }) }
}
