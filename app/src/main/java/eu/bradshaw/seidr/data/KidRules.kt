package eu.bradshaw.seidr.data

import eu.bradshaw.seidr.data.api.Album
import eu.bradshaw.seidr.data.api.Artist
import eu.bradshaw.seidr.data.api.Playlist
import eu.bradshaw.seidr.data.api.SearchResult
import eu.bradshaw.seidr.data.api.Song
import kotlinx.serialization.Serializable

/** One song on a kids' list: enough to tell which albums and artists it opens up. */
@Serializable
data class KidSong(val id: String, val albumId: String? = null, val artistIds: List<String> = emptyList())

/**
 * The three server playlists a kid's library is built from, as fetched. Pure,
 * so `KidRulesTest` can pin every rule down.
 *
 * Approved: playable, title and art shown. Otherwise a song shows (disabled)
 * only if it's in Safe Titles or Safe Art, with just what that list allows;
 * a song on no list doesn't show at all. Albums and artists show while
 * they have a song that shows; artist pictures never do. Playlists show
 * with their songs filtered and no cover (a mosaic of any song in them).
 * The three lists themselves are hidden from everyone (`Library`).
 */
@Serializable
data class KidLists(
    val approved: List<KidSong> = emptyList(),
    val titles: List<KidSong> = emptyList(),
    val art: List<KidSong> = emptyList(),
    /** Ids of the trusted playlists these came from, so a live edit to one is noticed. */
    val playlistIds: List<String> = emptyList(),
    /** Each of those playlists' `changed|songCount` when fetched: unchanged, they aren't fetched again. */
    val versions: Map<String, String> = emptyMap(),
)

class KidRules(val lists: KidLists) {
    private val approved = lists.approved.mapTo(HashSet()) { it.id }
    private val titles = lists.titles.mapTo(HashSet()) { it.id }
    private val art = lists.art.mapTo(HashSet()) { it.id }
    private val all = lists.approved + lists.titles + lists.art
    private val albums = all.mapNotNullTo(HashSet()) { it.albumId }
    private val artists = all.flatMapTo(HashSet()) { it.artistIds }
    private val artAlbums = (lists.approved + lists.art).mapNotNullTo(HashSet()) { it.albumId }
    val playlistIds = lists.playlistIds.toSet()

    fun playable(id: String) = id in approved
    fun visible(id: String) = id in approved || id in titles || id in art
    fun showTitle(id: String) = id in approved || id in titles
    fun showArt(id: String) = id in approved || id in art
    fun albumShows(id: String) = id in albums
    fun albumArtShows(id: String) = id in artAlbums
    private val playableArtists = lists.approved.flatMapTo(HashSet()) { it.artistIds }
    fun artistPlayable(id: String) = id in playableArtists

    /**
     * Whether a cover id's art may be served (`CoverProvider`, which other apps
     * can ask). Navidrome names covers by what they belong to: `mf-<song>`,
     * `al-<album>`; artist and playlist art never shows, and anything else fails closed.
     */
    fun coverAllowed(coverId: String): Boolean {
        val id = coverId.drop(3).substringBefore('_')
        return when (coverId.take(3)) {
            "mf-" -> showArt(id)
            "al-" -> albumArtShows(id)
            else -> false
        }
    }

    /** The song as a kid sees it, or null if it doesn't show. */
    fun song(s: Song): Song? = when {
        playable(s.id) -> s
        !visible(s.id) -> null
        else -> s.copy(
            title = if (showTitle(s.id)) s.title else HIDDEN_TITLE,
            coverArt = if (showArt(s.id)) s.coverArt else null,
            locked = true,
        )
    }

    fun songs(list: List<Song>): List<Song> = list.mapNotNull(::song)

    /** Null if no song on it shows. Its own songs (when fetched with them) are filtered too. */
    fun album(a: Album): Album? {
        if (a.id !in albums) return null
        // Counts stay the server's: screens add how many are approved.
        return a.copy(coverArt = a.coverArt.takeIf { a.id in artAlbums }, song = songs(a.song))
    }

    fun albums(list: List<Album>): List<Album> = list.mapNotNull(::album)

    fun artist(a: Artist): Artist? =
        if (a.id !in artists) null
        else a.copy(coverArt = null, album = albums(a.album))

    fun artists(list: List<Artist>): List<Artist> = list.mapNotNull(::artist)

    /** Filtered, without its cover; the three lists themselves never show. */
    fun playlist(p: Playlist): Playlist? {
        if (isKidsList(p.name)) return null
        return p.copy(coverArt = null, entry = songs(p.entry))
    }

    fun playlists(list: List<Playlist>): List<Playlist> = list.mapNotNull(::playlist)

    fun search(r: SearchResult) = SearchResult(artists(r.artist), albums(r.album), songs(r.song))

    companion object {
        const val APPROVED = "Seidr: Kids Approved"
        const val SAFE_TITLES = "Seidr: Kids Safe Titles"
        const val SAFE_ART = "Seidr: Kids Safe Art"
        val NAMES = listOf(APPROVED, SAFE_TITLES, SAFE_ART)

        /** Shown in place of a title that isn't on Safe Titles. */
        const val HIDDEN_TITLE = "Waiting for approval"

        fun isKidsList(name: String) = name.trim() in NAMES

        /** A playlist counts only with the exact name and owned by the grown-up who signed the kid in. */
        fun trusted(p: Playlist, name: String, owner: String) =
            p.name.trim() == name && p.owner?.trim().equals(owner.trim(), ignoreCase = true)

        fun kidSong(s: Song) = KidSong(s.id, s.albumId, (listOfNotNull(s.artistId) + s.artists.mapNotNull { it.id }).distinct())
    }
}
