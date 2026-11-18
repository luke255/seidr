package eu.bradshaw.seidr.data.api

import java.io.File
import java.security.MessageDigest

/**
 * Last-seen copies of read-only API responses, so screens can still show a
 * downloaded playlist when the server is out of reach.
 *
 * This is a display fallback only. The sync engine and playlist edits use the
 * strict client, which never reads from here: deciding anything (especially a
 * deletion) from a stale copy would break the "server is the truth" rule.
 */
class ResponseStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    fun put(key: String, body: String) {
        val f = file(key)
        val tmp = File(dir, f.name + ".tmp")
        // Synced before the rename, or a crash or power cut can leave an empty file in its place.
        java.io.FileOutputStream(tmp).use { it.write(body.toByteArray()); it.fd.sync() }
        tmp.renameTo(f)
    }

    fun get(key: String): String? = file(key).takeIf { it.isFile }?.readText()?.takeIf { it.isNotEmpty() }

    fun has(key: String): Boolean = file(key).let { it.isFile && it.length() > 0 }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        /** Endpoints worth keeping. Writes, streams and art never go through here. */
        val CACHEABLE = setOf(
            "getPlaylists.view", "getPlaylist.view", "getAlbum.view", "getArtist.view",
            "getArtists.view", "getAlbumList2.view", "getStarred2.view", "getTopSongs.view",
            "getLyricsBySongId.view", "getLyrics.view",
        )
    }
}
