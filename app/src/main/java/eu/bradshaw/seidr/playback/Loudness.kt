package eu.bradshaw.seidr.playback

import kotlin.math.pow

/**
 * Settings → Even out volume: ReplayGain, applied as the player's volume
 * (through [FadingPlayer], so it composes with the fades). Volume only goes
 * down, never up, so nothing can clip and a positive gain plays as recorded.
 */
object Loudness {
    /**
     * Songs without tags are turned down by this much, about where tagged
     * modern music sits, so they don't jump out next to tagged ones.
     */
    const val UNTAGGED_DB = -8f

    /** A linear volume (0..1) for a song with these gains, in dB. */
    fun volume(trackDb: Float?, albumDb: Float?, albumMode: Boolean): Float {
        val db = (if (albumMode) albumDb ?: trackDb else trackDb ?: albumDb) ?: UNTAGGED_DB
        return 10f.pow(db.coerceAtMost(0f) / 20f)
    }

    /**
     * Album levels while an album plays in order: the song next to this one,
     * either side in the queue, is from the same album. Shuffled, or a mix,
     * each song gets its own level.
     */
    fun albumMode(albumId: String?, before: String?, after: String?): Boolean =
        albumId != null && (albumId == before || albumId == after)
}
