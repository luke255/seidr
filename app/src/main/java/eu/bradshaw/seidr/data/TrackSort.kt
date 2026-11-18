package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Song

/**
 * How a playlist's tracks are shown. Only the view changes: the server's
 * order is the playlist's own ([CUSTOM]), and only Reorder ever writes it.
 * Ties fall through artist, album and track; songs alike in all of those keep
 * the playlist's order (the sorts are stable).
 */
enum class TrackSort(val key: String) {
    CUSTOM("custom"), TITLE("title"), ARTIST("artist"), ALBUM("album"), YEAR("year"), PLAYS("plays");

    /** [songs] in this order, each with its index in the playlist (which removes go by). */
    fun sort(songs: List<Song>): List<IndexedValue<Song>> {
        val all = songs.withIndex().toList()
        fun by(name: (Song) -> String?) = nameOrder<IndexedValue<Song>> { name(it.value).orEmpty() }
        val inAlbum = compareBy<IndexedValue<Song>> { it.value.discNumber ?: 0 }.thenBy { it.value.track ?: 0 }
        // Ties fall through artist, album, then the album's own order.
        val rest = by { it.artistLine }.then(by { it.album }).then(inAlbum)
        return when (this) {
            CUSTOM -> all
            TITLE -> all.sortedWith(by { it.title }.then(rest))
            ARTIST -> all.sortedWith(rest)
            ALBUM -> all.sortedWith(by { it.album }.then(rest))
            // Newest first, as Library's Year is.
            YEAR -> all.sortedWith(compareByDescending<IndexedValue<Song>> { it.value.year ?: 0 }.then(rest))
            PLAYS -> all.sortedWith(compareByDescending<IndexedValue<Song>> { it.value.playCount ?: 0L }.then(rest))
        }
    }

    companion object {
        fun of(key: String?, fallback: TrackSort = CUSTOM): TrackSort = entries.firstOrNull { it.key == key } ?: fallback
    }
}
