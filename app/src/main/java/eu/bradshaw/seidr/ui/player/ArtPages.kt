package eu.bradshaw.seidr.ui.player

import eu.bradshaw.seidr.playback.QueueEntry

/**
 * One page of Now Playing's cover pager. At rest there's a page per track
 * ([trackPages]), so a swipe always steps one track. While the app slides the
 * cover itself it uses [artPages], where tracks in a row that share a cover
 * share a page, so a slide never passes the same cover twice.
 *
 * [art] says which cover it is, [cover] is the id to load, and [land] is the
 * queue index that swiping to this page skips to.
 */
data class ArtPage(val key: String, val art: String, val cover: String?, val land: Int)

/**
 * The cover a queue entry shows. Navidrome gives each song its own cover id
 * (`mf-…`) even when it's the album's art, so the album decides.
 */
fun artOf(e: QueueEntry): String = e.albumId?.let { "al:$it" } ?: e.coverArt?.let { "ca:$it" } ?: "q:${e.key}"

/** A page per track: the pager at rest. */
fun trackPages(queue: List<QueueEntry>): List<ArtPage> =
    queue.mapIndexed { i, e -> ArtPage(e.key, artOf(e), e.coverArt, i) }

/**
 * The pager's pages while sliding, for [queue] playing at [index]. Each run of entries with
 * the same cover is one page, except the playing track's run, which splits
 * into before / current / after so swiping still steps a track at a time
 * within an album. Swiping to another run lands on its track nearest the
 * current one: the first ahead, the last behind.
 */
fun artPages(queue: List<QueueEntry>, index: Int): List<ArtPage> {
    val pages = mutableListOf<ArtPage>()
    fun page(at: Int) = ArtPage(queue[at].key, artOf(queue[at]), queue[at].coverArt, at)
    var start = 0
    while (start < queue.size) {
        val art = artOf(queue[start])
        var end = start
        while (end + 1 < queue.size && artOf(queue[end + 1]) == art) end++
        if (index in start..end) {
            if (index > start) pages += page(index - 1)
            pages += page(index)
            if (index < end) pages += page(index + 1)
        } else {
            pages += page(if (end < index) end else start)
        }
        start = end + 1
    }
    return pages
}
