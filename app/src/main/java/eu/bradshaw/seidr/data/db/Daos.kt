package eu.bradshaw.seidr.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SourceUsage(val sourceId: String, val tracks: Int, val bytes: Long, val done: Int)

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_source") suspend fun sources(): List<OfflineSource>
    @Query("SELECT * FROM offline_source") fun sourcesFlow(): Flow<List<OfflineSource>>
    @Query("SELECT * FROM offline_source WHERE id = :id") suspend fun source(id: String): OfflineSource?
    @Upsert suspend fun upsertSource(source: OfflineSource)
    @Query("DELETE FROM offline_source WHERE id = :id") suspend fun deleteSourceRow(id: String)
    @Query("DELETE FROM claim WHERE sourceId = :id") suspend fun deleteClaimsOf(id: String)

    /** Removes a source and all its claims (explicitly, not relying on FK cascade). */
    @Transaction
    suspend fun deleteSource(id: String) {
        deleteClaimsOf(id)
        deleteSourceRow(id)
    }

    @Query("SELECT trackId FROM claim WHERE sourceId = :sourceId") suspend fun claimsFor(sourceId: String): List<String>
    @Query("SELECT DISTINCT trackId FROM claim") suspend fun claimedTrackIds(): List<String>
    @Query("SELECT COUNT(*) FROM claim WHERE trackId = :trackId") suspend fun claimCount(trackId: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertClaims(claims: List<Claim>)
    @Query("DELETE FROM claim WHERE sourceId = :sourceId AND trackId IN (:trackIds)")
    suspend fun deleteClaims(sourceId: String, trackIds: List<String>)

    @Transaction
    suspend fun applyClaims(sourceId: String, add: Collection<String>, remove: Collection<String>) {
        if (add.isNotEmpty()) insertClaims(add.map { Claim(it, sourceId) })
        // SQLite caps bound parameters, so chunk big playlists.
        remove.chunked(500).forEach { deleteClaims(sourceId, it) }
    }

    @Query("SELECT * FROM download") suspend fun downloads(): List<Download>
    @Query("SELECT * FROM download") fun downloadsFlow(): Flow<List<Download>>
    @Query("SELECT * FROM download WHERE trackId = :id") suspend fun download(id: String): Download?
    @Query("SELECT * FROM download WHERE state IN ('queued','downloading','failed') ORDER BY updatedAt")
    suspend fun pendingDownloads(): List<Download>
    @Upsert suspend fun upsertDownload(d: Download)
    @Upsert suspend fun upsertDownloads(d: List<Download>)
    @Query("DELETE FROM download WHERE trackId = :id") suspend fun deleteDownload(id: String)
    @Query("UPDATE download SET state = 'queued', error = NULL WHERE state = 'failed'") suspend fun retryFailed()
    @Query("UPDATE download SET state = 'queued' WHERE state = 'downloading'") suspend fun resetInterrupted()
    @Query("UPDATE download SET state = :state, error = :error, updatedAt = :now WHERE trackId = :id")
    suspend fun setState(id: String, state: String, error: String? = null, now: Long = System.currentTimeMillis())
    @Query("UPDATE download SET bytesDone = :bytes WHERE trackId = :id") suspend fun setProgress(id: String, bytes: Long)

    @Query("""
        SELECT c.sourceId AS sourceId, COUNT(*) AS tracks,
               COALESCE(SUM(CASE WHEN d.state = 'done' THEN d.size ELSE 0 END), 0) AS bytes,
               COALESCE(SUM(CASE WHEN d.state = 'done' THEN 1 ELSE 0 END), 0) AS done
        FROM claim c LEFT JOIN download d ON d.trackId = c.trackId
        GROUP BY c.sourceId
    """)
    fun usageFlow(): Flow<List<SourceUsage>>

    @Insert suspend fun insertEdit(edit: PendingEdit)
    @Query("SELECT * FROM pending_edit ORDER BY id") suspend fun edits(): List<PendingEdit>
    @Query("SELECT COUNT(*) FROM pending_edit") fun editCount(): Flow<Int>
    @Query("DELETE FROM pending_edit WHERE id = :id") suspend fun deleteEdit(id: Long)

    @Insert suspend fun insertScrobble(s: PendingScrobble)
    @Query("SELECT * FROM pending_scrobble ORDER BY time") suspend fun scrobbles(): List<PendingScrobble>
    @Query("DELETE FROM pending_scrobble WHERE id = :id") suspend fun deleteScrobble(id: Long)
}

/** Playlist history: newest first is highest id first. */
@Dao
interface HistoryDao {
    @Insert suspend fun insert(version: PlaylistVersion): Long
    @Query("SELECT * FROM playlist_version WHERE playlistId = :playlistId ORDER BY id DESC LIMIT 1")
    suspend fun newest(playlistId: String): PlaylistVersion?
    @Query("SELECT * FROM playlist_version WHERE playlistId = :playlistId ORDER BY id DESC")
    fun versions(playlistId: String): Flow<List<PlaylistVersion>>
    @Query("SELECT DISTINCT playlistId FROM playlist_version") suspend fun playlistIds(): List<String>
    @Query("SELECT * FROM playlist_version WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun deleted(): Flow<List<PlaylistVersion>>
    @Query("UPDATE playlist_version SET deletedAt = :at WHERE id = :versionId") suspend fun setDeleted(versionId: Long, at: Long?)
    @Query(
        "DELETE FROM playlist_version WHERE playlistId = :playlistId AND id NOT IN " +
            "(SELECT id FROM playlist_version WHERE playlistId = :playlistId ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun keepNewest(playlistId: String, keep: Int)
    /** Versions older than [before], except each playlist's newest (what it's compared against). */
    @Query("DELETE FROM playlist_version WHERE savedAt < :before AND id NOT IN (SELECT MAX(id) FROM playlist_version GROUP BY playlistId)")
    suspend fun dropOlderThan(before: Long)
    @Query("DELETE FROM playlist_version WHERE playlistId IN (SELECT playlistId FROM playlist_version WHERE deletedAt < :before)")
    suspend fun dropDeletedBefore(before: Long)
    @Query("DELETE FROM playlist_version") suspend fun clear()
    /** A deleted playlist came back as [to]: its history goes with it. */
    @Query("UPDATE playlist_version SET playlistId = :to, deletedAt = NULL WHERE playlistId = :from")
    suspend fun move(from: String, to: String)
}
