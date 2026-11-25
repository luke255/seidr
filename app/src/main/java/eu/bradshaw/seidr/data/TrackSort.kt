package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Song

/**
 * How a playlist's tracks are shown. Only the view changes: the server's
 * order is the playlist's own ([CUSTOM]), and only Reorder ever writes it.
 * Each goes either way ([TrackOrder]); [firstDescending] is the way a first
 * pick goes. Ties fall through artist, album and track (always forwards);
 * songs alike in all of those keep the playlist's order (the sorts are stable).
 */
enum class TrackSort(val key: String, val firstDescending: Boolean = false) {
    CUSTOM("custom"), TITLE("title"), ARTIST("artist"), ALBUM("album"), YEAR("year"), PLAYS("plays", firstDescending = true);

    /** [songs] in this order, each with its index in the playlist (which removes go by). */
    fun sort(songs: List<Song>, descending: Boolean = firstDescending): List<IndexedValue<Song>> {
        val all = songs.withIndex().toList()
        fun by(name: (Song) -> String?) = nameOrder<IndexedValue<Song>> { name(it.value).orEmpty() }
        fun Comparator<IndexedValue<Song>>.way() = if (descending) reversed() else this
        val inAlbum = compareBy<IndexedValue<Song>> { it.value.discNumber ?: 0 }.thenBy { it.value.track ?: 0 }
        val rest = by { it.artistLine }.then(by { it.album }).then(inAlbum)
        return when (this) {
            CUSTOM -> if (descending) all.asReversed() else all
            TITLE -> all.sortedWith(by { it.title }.way().then(rest))
            ARTIST -> all.sortedWith(by { it.artistLine }.way().then(rest))
            ALBUM -> all.sortedWith(by { it.album }.way().then(rest))
            YEAR -> all.sortedWith(compareBy<IndexedValue<Song>> { it.value.year ?: 0 }.way().then(rest))
            PLAYS -> all.sortedWith(compareBy<IndexedValue<Song>> { it.value.playCount ?: 0L }.way().then(rest))
        }
    }

    companion object {
        fun of(key: String?): TrackSort? = entries.firstOrNull { it.key == key }
    }
}

/** A [TrackSort] and which way it goes. */
data class TrackOrder(val sort: TrackSort, val descending: Boolean = sort.firstDescending) {
    /** As kept in Settings: `title`, `title:desc`. */
    val key: String get() = sort.key + if (descending) DESC else ASC

    fun sort(songs: List<Song>): List<IndexedValue<Song>> = sort.sort(songs, descending)

    /** Picking [next]: the other way round if it's this sort already, else [next]'s first way. */
    fun pick(next: TrackSort): TrackOrder = if (next == sort) copy(descending = !descending) else TrackOrder(next)

    companion object {
        private const val DESC = ":desc"
        private const val ASC = ":asc"

        /** [key] as [TrackOrder.key] writes it, else [fallback]. A bare key (from before orders had a way) goes its old way: Year and Play count were newest and most first. */
        fun of(key: String?, fallback: TrackOrder = TrackOrder(TrackSort.CUSTOM)): TrackOrder {
            val k = key ?: return fallback
            val sort = TrackSort.of(k.substringBefore(':')) ?: return fallback
            return when {
                k.endsWith(DESC) -> TrackOrder(sort, true)
                k.endsWith(ASC) -> TrackOrder(sort, false)
                else -> TrackOrder(sort, sort == TrackSort.YEAR || sort == TrackSort.PLAYS)
            }
        }
    }
}
