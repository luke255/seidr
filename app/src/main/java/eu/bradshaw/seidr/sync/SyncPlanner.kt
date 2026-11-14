package eu.bradshaw.seidr.sync

/**
 * What the planner needs to know about a track on the server. The metadata
 * fields only feed the Downloads screen; decisions use id, size and suffix.
 */
data class ServerTrack(
    val id: String,
    val size: Long,
    val suffix: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
)

/** A row from the download table, as the planner sees it. */
data class LocalFile(
    val trackId: String,
    val done: Boolean,
    val size: Long,
    val suffix: String,
)

/**
 * The pure decision-making half of sync. No I/O, no Android: every rule about
 * what to download and what to delete lives here so it can be unit tested.
 *
 * The model: each downloaded track carries a set of claims (one per offline
 * playlist, album or the starred list that contains it). A file stays on the
 * phone while it has at least one claim.
 */
object SyncPlanner {

    data class ClaimDiff(val add: Set<String>, val remove: Set<String>) {
        val isEmpty get() = add.isEmpty() && remove.isEmpty()
    }

    /**
     * Compares a source's complete, trusted server track list with the claims
     * it currently holds. Duplicates in a playlist collapse to one claim.
     */
    fun diffClaims(serverTrackIds: Collection<String>, currentClaims: Collection<String>): ClaimDiff {
        val server = serverTrackIds.toSet()
        val current = currentClaims.toSet()
        return ClaimDiff(add = server - current, remove = current - server)
    }

    /**
     * The safety rule. A fetch may only drive claim removals if it is complete
     * and believable:
     *  - when the server states a count, the list must match it exactly
     *    (a truncated response must never be read as removals);
     *  - an empty list for a source that currently holds claims needs a second,
     *    independent confirmation that it really is empty.
     *
     * @param reportedCount the count the server reported alongside the list, if any
     * @param currentClaims how many claims the source holds now
     * @param emptyConfirmed true when a second signal agrees the source is empty
     */
    fun isTrustworthy(fetched: Int, reportedCount: Int?, currentClaims: Int, emptyConfirmed: Boolean): Boolean {
        if (reportedCount != null && fetched != reportedCount) return false
        if (fetched == 0 && currentClaims > 0 && !emptyConfirmed) return false
        return true
    }

    data class FilePlan(
        /** Claimed tracks with no file and no download row: queue them. */
        val queue: List<ServerTrack>,
        /** Downloaded files whose size or suffix no longer matches the server (re-tag, quality upgrade). */
        val redownload: List<ServerTrack>,
        /** Track ids with zero claims whose files should be removed now. */
        val delete: List<String>,
        /** Zero-claim tracks kept for now because they are playing; a later pass removes them. */
        val deferred: List<String>,
    )

    /**
     * Decides file-level actions from the claim table after this pass.
     *
     * @param claimed every track id with at least one claim
     * @param files every row in the download table (any state)
     * @param known server info for tracks fetched completely in this pass; used to
     *              queue new downloads and spot changed files. Tracks not fetched
     *              this pass are simply left alone.
     * @param protected track ids that must not be deleted yet (the playing track)
     */
    fun planFiles(
        claimed: Set<String>,
        files: List<LocalFile>,
        known: Map<String, ServerTrack>,
        protected: Set<String>,
    ): FilePlan {
        val byId = files.associateBy { it.trackId }
        val delete = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        for (f in files) {
            if (f.trackId in claimed) continue
            if (f.trackId in protected) deferred += f.trackId else delete += f.trackId
        }
        val queue = claimed.filter { it !in byId }.mapNotNull { known[it] }
        val redownload = files.filter { f ->
            f.done && f.trackId in claimed && known[f.trackId]?.let { s ->
                (s.size > 0 && s.size != f.size) || !s.suffix.equals(f.suffix, ignoreCase = true)
            } == true
        }.mapNotNull { known[it.trackId] }
        return FilePlan(queue, redownload, delete, deferred)
    }
}
