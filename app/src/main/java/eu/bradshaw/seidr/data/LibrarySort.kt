package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist

// Library's and Home's orders, each remembered (see Settings). Ties fall
// through artist, then album (then track, for songs: see TrackSort).

/** Playlists, the same on Home's shelf and in Library. */
enum class PlaylistSort(val key: String) {
    /** Last played (or made) on this device; never-played ones after, A–Z. */
    RECENT("recent"),
    NAME("name"),
    /** Last changed on the server. */
    UPDATED("updated"),
    /** The server's own order. */
    CUSTOM("custom");

    /**
     * [playlists] in this order, with [loved] (as null) put in: first by name or the
     * server's order, else where its newest play or love places it.
     */
    fun sort(playlists: List<Playlist>, lastPlayed: Map<String, Long>, loved: LovedAt?): List<Playlist?> {
        val byName = nameOrder<Playlist> { it.name }
        val sorted = when (this) {
            RECENT -> playlists.sortedWith(compareByDescending<Playlist> { recency(it, lastPlayed) }.then(byName))
            NAME -> playlists.sortedWith(byName)
            UPDATED -> playlists.sortedWith(compareByDescending<Playlist> { epochMs(it.changed) }.then(byName))
            CUSTOM -> playlists
        }
        if (loved == null) return sorted
        val at = when (this) {
            RECENT -> {
                val l = maxOf(loved.played ?: 0L, epochMs(loved.lastLoved))
                sorted.indexOfFirst { recency(it, lastPlayed) <= l }
            }
            UPDATED -> epochMs(loved.lastLoved).let { l -> sorted.indexOfFirst { epochMs(it.changed) <= l } }
            NAME, CUSTOM -> 0
        }.let { if (it < 0) sorted.size else it }
        return sorted.toMutableList<Playlist?>().apply { add(at, null) }
    }

    companion object {
        fun of(key: String?, default: PlaylistSort = RECENT) = entries.firstOrNull { it.key == key } ?: default
    }
}

/** The built-in Loved playlist's times: last played on this device (epoch ms), and its newest love (ISO). */
data class LovedAt(val played: Long?, val lastLoved: String?)

enum class AlbumSort(val key: String) {
    NAME("name"), ARTIST("artist"), ADDED("added"), YEAR("year"), PLAYS("plays"),
    /** Most recently saved first, then the rest. */
    SAVED("saved");

    /**
     * [saved]: the albums saved now (`Library.starredAlbums`), which beats each
     * album's own `starred` (the all-albums list is cached, so it can be stale).
     * Empty means not known yet, so `starred` alone decides.
     */
    fun sort(albums: List<Album>, saved: Set<String> = emptySet()): List<Album> {
        val artist = nameOrder<Album> { it.artistLine }
        val name = nameOrder<Album> { it.name }
        val rest = artist.then(name)
        return albums.sortedWith(
            when (this) {
                NAME -> name.then(artist)
                ARTIST -> rest
                ADDED -> compareByDescending<Album> { it.created.orEmpty() }.then(rest)
                YEAR -> compareByDescending<Album> { it.year ?: 0 }.then(rest)
                PLAYS -> compareByDescending<Album> { it.playCount ?: 0L }.then(rest)
                SAVED -> compareByDescending<Album> {
                    when {
                        saved.isEmpty() -> it.starred.orEmpty()
                        it.id !in saved -> ""
                        // Saved since the list was fetched: newest of all.
                        else -> it.starred ?: "~"
                    }
                }.then(rest)
            }
        )
    }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NAME
    }
}

enum class ArtistSort(val key: String) {
    NAME("name"), ALBUMS("albums"), PLAYS("plays");

    /** [plays]: each artist's plays, by id (Navidrome counts per album, so it's the sum of theirs). */
    fun sort(artists: List<Artist>, plays: () -> Map<String?, Long>): List<Artist> {
        val name = nameOrder<Artist> { it.name }
        return when (this) {
            NAME -> artists.sortedWith(name)
            ALBUMS -> artists.sortedWith(compareByDescending<Artist> { it.albumCount }.then(name))
            PLAYS -> plays().let { p -> artists.sortedWith(compareByDescending<Artist> { p[it.id] ?: 0L }.then(name)) }
        }
    }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: NAME

        /** Each album artist's plays: the sum of their albums'. */
        fun plays(albums: List<Album>): Map<String?, Long> =
            albums.groupBy { it.artistId }.mapValues { (_, a) -> a.sumOf { it.playCount ?: 0L } }
    }
}

private fun recency(p: Playlist, lastPlayed: Map<String, Long>): Long = lastPlayed[p.id] ?: 0L

internal fun epochMs(iso: String?): Long = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
