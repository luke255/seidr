package eu.bradshaw.seidr.sync

import android.util.Log
import eu.bradshaw.seidr.data.api.SubsonicApi
import eu.bradshaw.seidr.data.api.SubsonicException
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.data.db.EditType
import eu.bradshaw.seidr.data.db.OfflineDao
import eu.bradshaw.seidr.data.db.PendingEdit
import eu.bradshaw.seidr.data.db.SourceType
import java.io.IOException

sealed interface EditResult {
    /** Written to the server. */
    data object Applied : EditResult
    /** Server out of reach: saved and replayed on reconnect. */
    data object Queued : EditResult
    /** The playlist changed elsewhere since it was loaded; nothing was written. */
    data object Conflict : EditResult
    data class Failed(val message: String) : EditResult
}

/**
 * Every playlist write goes through here. Online edits go straight to the
 * server and are followed by a sync of that playlist; the app never treats its
 * own idea of the list as the truth. Offline adds, removes and renames are
 * stored as intents and replayed against a fresh fetch.
 *
 * Create, delete and reorder need the server there and then: reorder has to
 * rewrite the whole list, which is only safe against a list we just fetched.
 */
/** Where offline edits wait (Room in the app, a list in tests). */
interface EditQueue {
    suspend fun insertEdit(edit: PendingEdit)
    suspend fun edits(): List<PendingEdit>
    suspend fun deleteEdit(id: Long)
}

fun OfflineDao.asEditQueue() = object : EditQueue {
    override suspend fun insertEdit(edit: PendingEdit) = this@asEditQueue.insertEdit(edit)
    override suspend fun edits() = this@asEditQueue.edits()
    override suspend fun deleteEdit(id: Long) = this@asEditQueue.deleteEdit(id)
}

class PlaylistEditor(
    private val api: () -> SubsonicApi,
    private val dao: EditQueue,
    private val afterEdit: (playlistId: String) -> Unit,
) {
    private suspend fun fresh(playlistId: String): List<String> =
        api().getPlaylist(playlistId).ok().playlist?.entry.orEmpty().map { it.id }

    private suspend fun attempt(playlistId: String, queued: PendingEdit?, block: suspend () -> Unit): EditResult = try {
        block()
        afterEdit(playlistId)
        EditResult.Applied
    } catch (e: SubsonicException) {
        EditResult.Failed(e.message ?: "Server error")
    } catch (e: IOException) {
        if (queued != null) {
            dao.insertEdit(queued)
            EditResult.Queued
        } else EditResult.Failed("Can't reach the server")
    }

    suspend fun add(playlistId: String, songIds: List<String>): EditResult =
        if (songIds.size == 1) {
            attempt(playlistId, PendingEdit(playlistId = playlistId, type = EditType.ADD, songId = songIds[0])) {
                api().updatePlaylist(playlistId, songIdsToAdd = songIds).ok()
            }
        } else {
            // Multi-song adds are queued one intent per song if offline.
            try {
                api().updatePlaylist(playlistId, songIdsToAdd = songIds).ok()
                afterEdit(playlistId)
                EditResult.Applied
            } catch (e: SubsonicException) {
                EditResult.Failed(e.message ?: "Server error")
            } catch (e: IOException) {
                songIds.forEach { dao.insertEdit(PendingEdit(playlistId = playlistId, type = EditType.ADD, songId = it)) }
                EditResult.Queued
            }
        }

    suspend fun remove(playlistId: String, songId: String, indexHint: Int?): EditResult =
        attempt(playlistId, PendingEdit(playlistId = playlistId, type = EditType.REMOVE, songId = songId, indexHint = indexHint)) {
            val index = EditResolver.removeIndex(fresh(playlistId), songId, indexHint)
            if (index != null) api().updatePlaylist(playlistId, songIndexesToRemove = listOf(index)).ok()
        }

    suspend fun rename(playlistId: String, name: String): EditResult =
        attempt(playlistId, PendingEdit(playlistId = playlistId, type = EditType.RENAME, name = name)) {
            api().updatePlaylist(playlistId, name = name).ok()
        }

    suspend fun reorder(playlistId: String, seen: List<String>, desired: List<String>): EditResult {
        var conflict = false
        val r = attempt(playlistId, null) {
            val order = EditResolver.reorder(fresh(playlistId), seen, desired)
            if (order == null) conflict = true
            else api().replacePlaylistSongs(playlistId, order).ok()
        }
        return if (conflict) EditResult.Conflict else r
    }

    /**
     * Returns the new playlist's id, or null on failure. New playlists are
     * private: createPlaylist takes no visibility, so the server's default
     * (Navidrome can make new ones public) is undone straight after.
     */
    suspend fun create(name: String, songIds: List<String>): String? = try {
        val pl = api().createPlaylist(name, songIds).ok().playlist
        if (pl != null && pl.public) {
            runCatching { api().updatePlaylist(pl.id, public = false).ok() }
                .onFailure { Log.w("Editor", "couldn't make ${pl.id} private: $it") }
        }
        pl?.id
    } catch (e: Exception) {
        Log.w("Editor", "create failed: $e")
        null
    }

    suspend fun delete(playlistId: String): EditResult = attempt(playlistId, null) {
        api().deletePlaylist(playlistId).ok()
    }

    /**
     * Replays offline edits in order. Each is resolved by song id against a
     * fresh fetch; one whose target is already gone is dropped. Stops at the
     * first network failure and keeps the rest for next time.
     */
    suspend fun replayPending() {
        for (edit in dao.edits()) {
            try {
                when (edit.type) {
                    EditType.ADD -> api().updatePlaylist(edit.playlistId, songIdsToAdd = listOfNotNull(edit.songId)).ok()
                    EditType.REMOVE -> {
                        val index = EditResolver.removeIndex(fresh(edit.playlistId), edit.songId ?: "", edit.indexHint)
                        if (index != null) api().updatePlaylist(edit.playlistId, songIndexesToRemove = listOf(index)).ok()
                    }
                    EditType.RENAME -> api().updatePlaylist(edit.playlistId, name = edit.name).ok()
                }
                dao.deleteEdit(edit.id)
            } catch (e: SubsonicException) {
                // The server rejected it (playlist deleted, song gone): nothing to retry.
                Log.w("Editor", "dropping edit $edit: ${e.message}")
                dao.deleteEdit(edit.id)
            } catch (e: IOException) {
                return
            }
        }
    }

    companion object {
        fun sourceId(playlistId: String) = SourceType.sourceId(SourceType.PLAYLIST, playlistId)
    }
}
