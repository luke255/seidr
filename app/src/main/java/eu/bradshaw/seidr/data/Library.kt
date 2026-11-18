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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Read access for screens, over the lenient API (falls back to the last-seen
 * response when offline). Nothing here is ever used to decide a deletion.
 *
 * Signed in as a kid, everything it hands out is filtered by [kids]' rules
 * (songs, albums, artists, playlists, lyrics), so screens, the car and
 * search all see the same kid's-eye view.
 */
class Library(
    private val client: SubsonicClient,
    private val hideBad: StateFlow<Boolean>,
    /** The login in use (signed in, else the guest), lowercased. */
    private val user: StateFlow<String?>,
    private val showOthersPrivate: StateFlow<Boolean>,
    private val kids: Kids,
    private val scope: CoroutineScope,
) {
    /** The main library's safety net, told of every full fetch and listing; null for a kid view's. */
    var history: PlaylistHistory? = null

    private val api get() = client.api
    private val kid: KidRules? get() = kids.current

    private val _allPlaylists = MutableStateFlow<List<Playlist>?>(null)
    /** Every playlist, including the "Bad: " ones that [playlists] hides. */
    val allPlaylists: StateFlow<List<Playlist>?> = _allPlaylists.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>?>(null)
    /**
     * The playlists screens show: "Bad: " ones are left out while that
     * integration is on, and other users' private ones (which admins get)
     * unless Settings asks for them.
     */
    val playlists: StateFlow<List<Playlist>?> = _playlists.asStateFlow()

    init {
        scope.launch { hideBad.collect { publishPlaylists() } }
        scope.launch { kids.rules.collect { publishPlaylists() } }
        scope.launch { user.collect { publishPlaylists() } }
        scope.launch { showOthersPrivate.collect { publishPlaylists() } }
    }

    private fun visible(list: List<Playlist>): List<Playlist> {
        // The kids' lists are for whoever approves songs, not for browsing.
        val shown = list.filterNot {
            KidRules.isKidsList(it.name) || (hideBad.value && BadReports.isBadPlaylist(it.name)) ||
                (!showOthersPrivate.value && isOthersPrivate(it))
        }
        return kid?.playlists(shown) ?: shown
    }

    /** Someone else's private playlist, which the server shows only because this login is an admin. */
    fun isOthersPrivate(p: Playlist): Boolean = !p.public && isOthers(p)

    /**
     * Someone else's playlist: shared with everyone, or (for an admin) private.
     * Home and Library keep these apart from the user's own.
     */
    fun isOthers(p: Playlist): Boolean {
        val me = user.value ?: return false
        return p.owner != null && !p.owner.trim().equals(me, ignoreCase = true)
    }

    private fun publishPlaylists() { _playlists.value = _allPlaylists.value?.let(::visible) }

    private fun setAll(list: List<Playlist>?) { _allPlaylists.value = list; publishPlaylists() }

    private val _starredSongs = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of starred ("liked") songs; the heart everywhere reads from this. */
    val starredSongs: StateFlow<Set<String>> = _starredSongs.asStateFlow()

    private val _lastLoved = MutableStateFlow<String?>(null)
    /** When the newest loved song was loved (ISO 8601), for sorting Loved by "Recently updated". */
    val lastLoved: StateFlow<String?> = _lastLoved.asStateFlow()


    private val _playlistEdited = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /**
     * Ids of playlists just edited, here or elsewhere (live events, or a listing
     * that changed), so open screens reload and downloads follow.
     */
    val playlistEdited: SharedFlow<String> = _playlistEdited.asSharedFlow()
    fun notifyPlaylistEdited(id: String) { _playlistEdited.tryEmit(id) }

    private var artistsCache: List<Artist>? = null
    private var albumsCache: List<Album>? = null

    /** The server's music changed (a scan): the next artists/albums read fetches again. */
    fun forgetMusic() {
        artistsCache = null
        albumsCache = null
    }

    /** Refetches the list and returns the visible playlists (see [playlists]). */
    suspend fun refreshPlaylists(): List<Playlist> = visible(fetchAllPlaylists())

    /**
     * Refetches every playlist. Any that changed since the last listing (or came
     * or went) is announced on [playlistEdited], so open screens reload and
     * downloads follow, whatever made the change.
     */
    suspend fun fetchAllPlaylists(): List<Playlist> {
        val list = api.getPlaylists().ok().playlists?.playlist.orEmpty()
        val before = _allPlaylists.value
        setAll(list)
        if (before != null) changedPlaylists(before, list).forEach(::notifyPlaylistEdited)
        history?.let { h -> scope.launch { h.listed(list) } }
        return list
    }

    suspend fun playlist(id: String): Playlist =
        (api.getPlaylist(id).ok().playlist ?: error("No playlist")).also(::rememberSongs)
            .also { p -> history?.takeUnless { isOthers(p) }?.let { h -> scope.launch { h.seen(p) } } }
            .also(::checkListing)
            .let(::forKid)

    private var listingJob: Job? = null

    /**
     * A full fetch newer than the listing's entry (or one the listing lacks) means
     * the listing is stale, so Home and Library's counts are wrong: fetch it again.
     * Smart playlists change on every read, so they're left to scans.
     */
    private fun checkListing(p: Playlist) {
        if (p.readonly) return
        val listed = _allPlaylists.value ?: return
        if (listed.find { it.id == p.id }?.changed == p.changed) return
        if (listingJob?.isActive == true) return
        listingJob = scope.launch { runCatching { fetchAllPlaylists() } }
    }

    private fun forKid(p: Playlist): Playlist = kid?.let { it.playlist(p) ?: error("No playlist") } ?: p
    private fun forKid(a: Album): Album = kid?.let { it.album(a) ?: error("No album") } ?: a

    // Last-seen copies, without touching the network: the car shows these at once
    // rather than wait on a patchy mobile connection, then refreshes behind them.
    fun cachedPlaylists(): List<Playlist>? =
        playlists.value ?: client.cached("getPlaylists.view")?.response?.playlists?.playlist?.let(::visible)
    fun cachedPlaylist(id: String): Playlist? = client.cached("getPlaylist.view", "id" to id)?.response?.playlist
        ?.let { p -> kid.let { k -> if (k == null) p else k.playlist(p) } }
    fun cachedStarredSongs(): List<Song>? = client.cached("getStarred2.view")?.response?.starred2?.song
        ?.sortedByDescending { it.starred ?: "" }?.let { s -> kid?.songs(s) ?: s }
    fun cachedAlbum(id: String): Album? = client.cached("getAlbum.view", "id" to id)?.response?.album
        ?.let { a -> kid.let { k -> if (k == null) a else k.album(a) } }

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
    suspend fun album(id: String): Album = (api.getAlbum(id).ok().album ?: error("No album")).let(::forKid)
    suspend fun artist(id: String): Artist = (api.getArtist(id).ok().artist ?: error("No artist"))
        .let { a -> kid?.let { it.artist(a) ?: error("No artist") } ?: a }
    suspend fun song(id: String): Song? = runCatching { api.getSong(id).ok().song }.getOrNull()
        ?.let { s -> kid.let { k -> if (k == null) s else k.song(s) } }

    suspend fun artists(refresh: Boolean = false): List<Artist> {
        val all = artistsCache.takeUnless { refresh }
            ?: api.getArtists().ok().artists?.index.orEmpty().flatMap { it.artist }.also { artistsCache = it }
        return kid?.artists(all) ?: all
    }

    /** Every album, paged in 500s. */
    suspend fun allAlbums(refresh: Boolean = false): List<Album> {
        val all = albumsCache.takeUnless { refresh } ?: fetchAllAlbums()
        return kid?.albums(all) ?: all
    }

    private suspend fun fetchAllAlbums(): List<Album> {
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

    suspend fun albumList(type: String, size: Int = 20): List<Album> {
        val k = kid ?: return api.getAlbumList2(type, size).ok().albumList2?.album.orEmpty()
        // Few albums pass, so look further down the list for enough that do.
        return k.albums(api.getAlbumList2(type, 500).ok().albumList2?.album.orEmpty()).take(size)
    }

    /** Loved songs, most recently loved first (the server's order isn't defined). */
    suspend fun starredSongsList(): List<Song> {
        val s = api.getStarred2().ok().starred2
        val songs = s?.song.orEmpty().sortedByDescending { it.starred ?: "" }
        _starredSongs.value = songs.map { it.id }.toSet()
        _lastLoved.value = songs.firstOrNull()?.starred
        return kid?.songs(songs) ?: songs
    }

    suspend fun refreshStarred() { runCatching { starredSongsList() } }

    suspend fun search(query: String): SearchResult {
        val k = kid ?: return api.search3(query).ok().searchResult3 ?: SearchResult()
        // Most hits won't pass, so ask for more and keep the usual numbers.
        val r = k.search(api.search3(query, 50, 100, 200).ok().searchResult3 ?: SearchResult())
        return SearchResult(r.artist.take(5), r.album.take(10), r.song.take(20))
    }

    suspend fun topSongs(artistName: String): List<Song> {
        val k = kid
        val top = runCatching { api.getTopSongs(artistName, if (k == null) 10 else 50).ok().topSongs?.song.orEmpty() }.getOrDefault(emptyList())
        return k?.songs(top)?.take(10) ?: top
    }

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

    // Recent lookups, including "none", so reopening Now Playing doesn't refetch.
    private val lyricsCache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 50
    }

    /**
     * Lyrics for a song, or null if the server has none. Throws when the server
     * can't be reached and there's no last-seen copy.
     */
    suspend fun lyrics(songId: String, artist: String, title: String): Lyrics? {
        // Kids get lyrics with approved songs only.
        if (kid?.playable(songId) == false) return null
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
        _lastLoved.value = null
    }
}

/**
 * Playlists whose listing entry changed between two listings, plus any added
 * or gone. Smart (readonly) playlists bump `changed` on every read, so only
 * their contents' size and name count.
 */
internal fun changedPlaylists(before: List<Playlist>, after: List<Playlist>): List<String> {
    val old = before.associateBy { it.id }
    val now = after.associateBy { it.id }
    return (old.keys + now.keys).filter { id ->
        val a = old[id]
        val b = now[id]
        when {
            a == null || b == null -> true
            a.name != b.name || a.songCount != b.songCount || a.duration != b.duration -> true
            b.readonly -> false
            else -> a.changed != b.changed || a.public != b.public || a.coverArt != b.coverArt
        }
    }
}
