package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.SearchResult
import eu.bradshaw.seidr.data.api.Song
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.api.SubsonicException
import eu.bradshaw.seidr.data.api.ok
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Read access for screens, over the lenient API (falls back to the last-seen
 * response when offline). Nothing here is ever used to decide a deletion.
 */
class Library(
    private val client: SubsonicClient,
    private val hideBad: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    private val api get() = client.api

    private val _allPlaylists = MutableStateFlow<List<Playlist>?>(null)
    /** Every playlist, including the "Bad: " ones that [playlists] hides. */
    val allPlaylists: StateFlow<List<Playlist>?> = _allPlaylists.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>?>(null)
    /** The playlists screens show: "Bad: " ones are left out while that integration is on. */
    val playlists: StateFlow<List<Playlist>?> = _playlists.asStateFlow()

    init {
        scope.launch { hideBad.collect { publishPlaylists() } }
    }

    private fun visible(list: List<Playlist>) =
        if (hideBad.value) list.filterNot { BadReports.isBadPlaylist(it.name) } else list

    private fun publishPlaylists() { _playlists.value = _allPlaylists.value?.let(::visible) }

    private fun setAll(list: List<Playlist>?) { _allPlaylists.value = list; publishPlaylists() }

    private val _starredSongs = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of starred ("liked") songs; the heart everywhere reads from this. */
    val starredSongs: StateFlow<Set<String>> = _starredSongs.asStateFlow()

    private val _starredAlbums = MutableStateFlow<Set<String>>(emptySet())
    val starredAlbums: StateFlow<Set<String>> = _starredAlbums.asStateFlow()

    private val _playlistEdited = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Ids of playlists just edited from this app, so open screens can reload. */
    val playlistEdited: SharedFlow<String> = _playlistEdited.asSharedFlow()
    fun notifyPlaylistEdited(id: String) { _playlistEdited.tryEmit(id) }

    private var artistsCache: List<Artist>? = null
    private var albumsCache: List<Album>? = null

    /** Refetches the list and returns the visible playlists (see [playlists]). */
    suspend fun refreshPlaylists(): List<Playlist> = visible(fetchAllPlaylists())

    suspend fun fetchAllPlaylists(): List<Playlist> =
        api.getPlaylists().ok().playlists?.playlist.orEmpty().also(::setAll)

    suspend fun playlist(id: String): Playlist =
        (api.getPlaylist(id).ok().playlist ?: error("No playlist")).also(::rememberSongs)

    // Last-seen copies, without touching the network: the car shows these at once
    // rather than wait on a patchy mobile connection, then refreshes behind them.
    fun cachedPlaylists(): List<Playlist>? =
        playlists.value ?: client.cached("getPlaylists.view")?.response?.playlists?.playlist?.let(::visible)
    fun cachedPlaylist(id: String): Playlist? = client.cached("getPlaylist.view", "id" to id)?.response?.playlist
    fun cachedAlbum(id: String): Album? = client.cached("getAlbum.view", "id" to id)?.response?.album

    private val _playlistSongs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /**
     * Song ids per playlist, as last seen. Lets a playlist whose tracks are all
     * downloaded by other means (inside a downloaded smart playlist, say) count
     * as available offline. Display only, like the rest of this class.
     */
    val playlistSongs: StateFlow<Map<String, List<String>>> = _playlistSongs.asStateFlow()
    private val seenChanged = mutableMapOf<String, String?>()
    @Volatile private var lastWarm = 0L

    private fun rememberSongs(p: Playlist) {
        synchronized(seenChanged) { seenChanged[p.id] = p.changed }
        _playlistSongs.update { it + (p.id to p.entry.map { e -> e.id }) }
    }

    /** Fills [playlists] and [playlistSongs] from the display cache, without the network. */
    fun loadCachedPlaylists() {
        val list = runCatching { client.cached("getPlaylists.view")?.ok()?.playlists?.playlist }.getOrNull() ?: return
        if (_allPlaylists.value == null) setAll(list)
        for (p in list) {
            runCatching { client.cached("getPlaylist.view", "id" to p.id)?.ok()?.playlist }.getOrNull()?.let(::rememberSongs)
        }
    }

    /**
     * Refreshes every playlist's cached track list, so any playlist can be opened
     * offline. Unchanged playlists are skipped; smart (readonly) ones always
     * look changed, so callers run this on unmetered networks only.
     */
    suspend fun warmPlaylists() {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 15 * 60_000L) return
        lastWarm = now
        for (p in fetchAllPlaylists()) {
            val known = synchronized(seenChanged) { seenChanged[p.id] }
            if (!p.readonly && p.changed != null && known == p.changed) continue
            runCatching { playlist(p.id) }
        }
    }
    suspend fun album(id: String): Album = api.getAlbum(id).ok().album ?: error("No album")
    suspend fun artist(id: String): Artist = api.getArtist(id).ok().artist ?: error("No artist")
    suspend fun song(id: String): Song? = runCatching { api.getSong(id).ok().song }.getOrNull()

    suspend fun artists(refresh: Boolean = false): List<Artist> {
        if (!refresh) artistsCache?.let { return it }
        return api.getArtists().ok().artists?.index.orEmpty().flatMap { it.artist }.also { artistsCache = it }
    }

    /** Every album, paged in 500s. */
    suspend fun allAlbums(refresh: Boolean = false): List<Album> {
        if (!refresh) albumsCache?.let { return it }
        val out = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val page = api.getAlbumList2("alphabeticalByName", 500, offset).ok().albumList2?.album.orEmpty()
            out += page
            if (page.size < 500) break
            offset += 500
        }
        return out.also { albumsCache = it }
    }

    suspend fun albumList(type: String, size: Int = 20): List<Album> =
        api.getAlbumList2(type, size).ok().albumList2?.album.orEmpty()

    suspend fun starredSongsList(): List<Song> {
        val s = api.getStarred2().ok().starred2
        val songs = s?.song.orEmpty()
        _starredSongs.value = songs.map { it.id }.toSet()
        _starredAlbums.value = s?.album.orEmpty().map { it.id }.toSet()
        return songs
    }

    suspend fun refreshStarred() { runCatching { starredSongsList() } }

    suspend fun search(query: String): SearchResult =
        api.search3(query).ok().searchResult3 ?: SearchResult()

    suspend fun topSongs(artistName: String): List<Song> =
        runCatching { api.getTopSongs(artistName, 10).ok().topSongs?.song.orEmpty() }.getOrDefault(emptyList())

    /** Optimistic: flips the heart, then writes to the server; reverts on failure. */
    suspend fun setSongStarred(id: String, starred: Boolean): Boolean {
        _starredSongs.update { if (starred) it + id else it - id }
        return try {
            if (starred) client.strict.star(id).ok() else client.strict.unstar(id).ok()
            true
        } catch (e: Exception) {
            _starredSongs.update { if (starred) it - id else it + id }
            false
        }
    }

    suspend fun setAlbumStarred(id: String, starred: Boolean): Boolean {
        _starredAlbums.update { if (starred) it + id else it - id }
        return try {
            if (starred) client.strict.starAlbum(id).ok() else client.strict.unstarAlbum(id).ok()
            true
        } catch (e: Exception) {
            _starredAlbums.update { if (starred) it - id else it + id }
            false
        }
    }

    // Recent lookups, including "none", so reopening Now Playing doesn't refetch.
    private val lyricsCache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 50
    }

    /**
     * Lyrics for a song, or null if the server has none. Throws when the server
     * can't be reached and there's no last-seen copy.
     */
    suspend fun lyrics(songId: String, artist: String, title: String): Lyrics? {
        synchronized(lyricsCache) { if (lyricsCache.containsKey(songId)) return lyricsCache[songId] }
        val result = try {
            Lyrics.from(api.getLyricsBySongId(songId).ok().lyricsList?.structuredLyrics.orEmpty())
        } catch (e: Exception) {
            // A server without the songLyrics extension answers with an error or a 404.
            if (e !is SubsonicException && e !is HttpException) throw e
            Lyrics.from(api.getLyrics(artist, title).ok().lyrics)
        }
        synchronized(lyricsCache) { lyricsCache[songId] = result }
        return result
    }

    fun clear() {
        synchronized(lyricsCache) { lyricsCache.clear() }
        setAll(null)
        artistsCache = null
        albumsCache = null
        _starredSongs.value = emptySet()
        _starredAlbums.value = emptySet()
    }
}
