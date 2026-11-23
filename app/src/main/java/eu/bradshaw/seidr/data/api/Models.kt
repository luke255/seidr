package eu.bradshaw.seidr.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// OpenSubsonic JSON shapes, trimmed to the fields Seiðr uses. Every field is
// optional or defaulted because servers differ in what they send.

@Serializable
data class Envelope(@SerialName("subsonic-response") val response: SubsonicResponse)

@Serializable
data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: ApiError? = null,
    val openSubsonicExtensions: List<Extension> = emptyList(),
    val randomSongs: SongList? = null,
    val playlists: PlaylistsWrapper? = null,
    val playlist: Playlist? = null,
    val albumList2: AlbumList? = null,
    val album: Album? = null,
    val artists: ArtistsIndex? = null,
    val artist: Artist? = null,
    val starred2: Starred? = null,
    val searchResult3: SearchResult? = null,
    val topSongs: SongList? = null,
    val song: Song? = null,
    val lyricsList: LyricsList? = null,
    val lyrics: PlainLyrics? = null,
    val bookmarks: Bookmarks? = null,
    val albumInfo: AlbumInfo? = null,
    val user: User? = null,
    val scanStatus: ScanStatus? = null,
    val nowPlaying: NowPlayingList? = null,
)

@Serializable
data class NowPlayingList(val entry: List<NowPlayingEntry> = emptyList())

/** A song someone is playing ([minutesAgo] since their app reported it), on the player named [playerName]. */
@Serializable
data class NowPlayingEntry(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int = 0,
    val username: String = "",
    val minutesAgo: Int = 0,
    val playerName: String? = null,
) {
    fun song() = Song(id, title, album, artist, displayArtist, albumId, artistId, coverArt = coverArt, duration = duration)
}

/** [lastScan] (Navidrome) and [count] move when a scan changes the library. */
@Serializable
data class ScanStatus(val scanning: Boolean = false, val count: Long? = null, val lastScan: String? = null)

@Serializable
data class User(val username: String = "", val adminRole: Boolean = false)

@Serializable
data class ApiError(val code: Int = 0, val message: String? = null)

@Serializable
data class ArtistRef(val id: String? = null, val name: String = "")

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val displayArtist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val coverArt: String? = null,
    val duration: Int = 0,
    val size: Long = 0,
    val suffix: String? = null,
    val contentType: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val starred: String? = null,
    val bitRate: Int? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val playCount: Long? = null,
    /** OpenSubsonic: loudness, in dB; absent or empty when the file has no ReplayGain tags. */
    val replayGain: ReplayGain? = null,
    /** Kid mode: shows, but isn't approved to play (see `KidRules`). Never from the server. */
    @kotlinx.serialization.Transient val locked: Boolean = false,
) {
    val artistLine: String get() = displayArtist?.takeIf { it.isNotBlank() } ?: artist ?: ""
}

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val displayArtist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val releaseTypes: List<String> = emptyList(),
    val isCompilation: Boolean = false,
    /** This user's plays (Navidrome's annotation). */
    val playCount: Long? = null,
    val song: List<Song> = emptyList(),
) {
    val artistLine: String get() = displayArtist?.takeIf { it.isNotBlank() } ?: artist ?: ""
}

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val album: List<Album> = emptyList(),
)

@Serializable
data class ArtistsIndex(val index: List<ArtistIndexEntry> = emptyList())

@Serializable
data class ArtistIndexEntry(val name: String = "", val artist: List<Artist> = emptyList())

@Serializable
data class PlaylistsWrapper(val playlist: List<Playlist> = emptyList())

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val readonly: Boolean = false,
    val entry: List<Song> = emptyList(),
)

@Serializable
data class AlbumList(val album: List<Album> = emptyList())

@Serializable
data class Starred(
    val song: List<Song> = emptyList(),
    val album: List<Album> = emptyList(),
    val artist: List<Artist> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class Extension(val name: String, val versions: List<Int> = emptyList())

@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
)

@Serializable
data class SongList(val song: List<Song> = emptyList())

@Serializable
data class LyricsList(val structuredLyrics: List<StructuredLyrics> = emptyList())

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    /** Milliseconds, carried over from LRC's `[offset:]` tag. */
    val offset: Long = 0,
    val line: List<LyricLine> = emptyList(),
)

/** [start] is in milliseconds, and only present for synced lyrics. */
@Serializable
data class LyricLine(val start: Long? = null, val value: String = "")

@Serializable
data class PlainLyrics(val artist: String? = null, val title: String? = null, val value: String? = null)

/** Navidrome's image URLs here are public, signed links to that one album's cover. */
@Serializable
data class AlbumInfo(val mediumImageUrl: String? = null, val largeImageUrl: String? = null)

@Serializable
data class Bookmarks(val bookmark: List<Bookmark> = emptyList())

@Serializable
data class Bookmark(val position: Long = 0, val comment: String? = null, val entry: Song? = null)
