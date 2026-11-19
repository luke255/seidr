package eu.bradshaw.seidr.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object SourceType {
    const val PLAYLIST = "playlist"
    const val ALBUM = "album"
    /** Loved songs (the server's starred songs). There's only one, so its id is fixed. */
    const val STARRED = "starred"

    fun sourceId(type: String, serverId: String) = "$type:$serverId"
    const val STARRED_ID = "starred:starred"
}

/** Something the user has switched Download on for. Its id is "<type>:<serverId>". */
@Entity(tableName = "offline_source")
data class OfflineSource(
    @PrimaryKey val id: String,
    val type: String,
    val serverId: String,
    val name: String,
    /** The playlist's `changed` timestamp at the last complete fetch; null for albums and Loved. */
    val changed: String? = null,
    val enabled: Boolean = true,
    /** Wall-clock millis of the last complete fetch; forces a periodic full re-check. */
    val lastFullFetch: Long = 0,
    val coverArt: String? = null,
)

/** Source [sourceId] wants track [trackId] on the phone. A file lives while it has at least one claim. */
@Entity(
    tableName = "claim",
    primaryKeys = ["trackId", "sourceId"],
    indices = [Index("sourceId")],
    foreignKeys = [ForeignKey(
        entity = OfflineSource::class, parentColumns = ["id"], childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class Claim(val trackId: String, val sourceId: String)

object DownloadState {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val DONE = "done"
    const val FAILED = "failed"
}

/** A track file on the phone (or on its way). Track metadata is kept for the Downloads screen. */
@Entity(tableName = "download")
data class Download(
    @PrimaryKey val trackId: String,
    val state: String,
    /** Absolute path once complete; while a re-download is under way, the previous version's. */
    val path: String? = null,
    /** Size and suffix the server reported when this download was queued. */
    val size: Long,
    val suffix: String,
    val bytesDone: Long = 0,
    val error: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

object EditType {
    const val ADD = "add"
    const val REMOVE = "remove"
    const val RENAME = "rename"
}

/**
 * A playlist edit made while the server was out of reach, stored as an intent
 * ("add song X to playlist P") and replayed against a fresh fetch.
 */
@Entity(tableName = "pending_edit")
data class PendingEdit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val type: String,
    val songId: String? = null,
    /** For removes: the index the user saw, used only to pick between duplicate songs. */
    val indexHint: Int? = null,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A play that couldn't be reported to the server yet. */
@Entity(tableName = "pending_scrobble")
data class PendingScrobble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val time: Long,
)

/**
 * One state of one of the user's playlists, kept so an earlier one (or a
 * deleted playlist) can be saved as a copy. See `PlaylistHistory`.
 */
@Entity(tableName = "playlist_version", indices = [Index("playlistId")])
data class PlaylistVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val name: String,
    /** Song ids in order, one per line. */
    val songIds: String,
    /** Wall-clock millis when this state was first seen. */
    val savedAt: Long,
    /** Set on the newest version only, once the playlist is gone from the server. */
    val deletedAt: Long? = null,
    /** A smart playlist's rules (Navidrome's JSON); its songs aren't kept, as the rules make them. */
    val rules: String? = null,
) {
    val songs: List<String> get() = if (songIds.isEmpty()) emptyList() else songIds.split('\n')
}
