package eu.bradshaw.seidr

import android.app.Application
import android.content.Context
import eu.bradshaw.seidr.data.BadReports
import eu.bradshaw.seidr.data.Library
import eu.bradshaw.seidr.data.LiveEvents
import eu.bradshaw.seidr.data.LyricsOffsets
import eu.bradshaw.seidr.data.AlbumPlays
import eu.bradshaw.seidr.data.PlaylistPlays
import eu.bradshaw.seidr.data.ServerStatus
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.data.api.ResponseStore
import eu.bradshaw.seidr.data.api.SubsonicClient
import eu.bradshaw.seidr.data.db.AppDatabase
import eu.bradshaw.seidr.playback.NowPlaying
import eu.bradshaw.seidr.playback.PlayerConnection
import eu.bradshaw.seidr.sync.DownloadIndex
import eu.bradshaw.seidr.sync.MusicFiles
import eu.bradshaw.seidr.sync.PlaylistEditor
import eu.bradshaw.seidr.sync.asEditQueue
import eu.bradshaw.seidr.sync.RoomSyncStore
import eu.bradshaw.seidr.sync.SubsonicSyncServer
import eu.bradshaw.seidr.sync.SyncCoordinator
import eu.bradshaw.seidr.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired singletons. One per process, created on first use. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(app)
    val status = ServerStatus(scope)

    /**
     * Signed out, the login of whoever is playing music here over Connect
     * (in memory only; see `ConnectSession`). The client uses it for
     * streams, art and scrobbles; sync never runs on it.
     */
    val guest = kotlinx.coroutines.flow.MutableStateFlow<eu.bradshaw.seidr.connect.GuestLogin?>(null)

    /** Whose preferences apply: the signed-in user, else the guest one. Lowercased username. */
    val user: kotlinx.coroutines.flow.StateFlow<String?> =
        kotlinx.coroutines.flow.combine(settings.server, guest) { s, g -> (s ?: g?.config)?.username?.trim()?.lowercase() }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settings.server.value?.username?.trim()?.lowercase())

    val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The theme to show: this device's, or while playing for a guest, theirs. */
    val theme: kotlinx.coroutines.flow.StateFlow<String> =
        kotlinx.coroutines.flow.combine(settings.theme, settings.server, guest) { own, s, g -> if (s == null) g?.theme ?: own else own }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settings.theme.value)

    val client = SubsonicClient({ settings.server.value ?: guest.value?.config }, settings.coverSalt, baseHttp, ResponseStore(File(app.filesDir, "api")), status)
    /** Kid mode's rules, while the login in use is a kid's (see `Kids`). */
    val kids = eu.bradshaw.seidr.data.Kids(
        loginNow = { settings.server.value ?: guest.value?.config },
        logins = kotlinx.coroutines.flow.combine(settings.server, guest) { s, g -> s ?: g?.config }
            .distinctUntilChanged(),
        api = { client.strict },
        dir = File(app.filesDir, "kids"),
        scope = scope,
    )

    /** Settings → Reporting, which kids don't get. */
    private val badReportsOn = kotlinx.coroutines.flow.combine(settings.badReports, settings.server) { on, s -> on && s?.isKid != true }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settings.badReports.value && settings.server.value?.isKid != true)

    val library = Library(client, badReportsOn, kids, scope)
    val db = AppDatabase.build(app)
    val files = MusicFiles(app)
    val downloads = DownloadIndex(db.offline(), scope)

    val engine = SyncEngine(
        server = SubsonicSyncServer { client.strict },
        store = RoomSyncStore(db.offline()),
        files = files,
        protectedIds = { setOfNotNull(NowPlaying.currentSongId) },
        gate = {
            // Kid mode: only approved songs download, by lists fetched fresh for this pass.
            if (!kids.isKid) null
            else (kids.refresh() ?: throw java.io.IOException("signed in as someone else")).let { r ->
                eu.bradshaw.seidr.sync.TrackGate(r.lists.approved.map { it.id }.toSet())
            }
        },
    )

    val playlistPlays = PlaylistPlays(app)
    val albumPlays = AlbumPlays(app)

    val lyricsOffsets = LyricsOffsets(app, { client.strict }, scope)

    val sync: SyncCoordinator

    val editor = PlaylistEditor(
        api = { client.strict },
        dao = db.offline().asEditQueue(),
        afterEdit = { id ->
            library.notifyPlaylistEdited(id)
            sync.request(setOf(PlaylistEditor.sourceId(id)))
        },
    )

    /** Loves changed (here or elsewhere): a downloaded Loved list follows them. */
    fun lovedChanged() {
        val id = eu.bradshaw.seidr.data.db.SourceType.STARRED_ID
        if (id in downloads.sources.value) sync.request(setOf(id))
    }

    val bad = BadReports(badReportsOn, library, editor, db.offline(), scope) { client.strict }

    init {
        sync = SyncCoordinator(app, engine, db.offline(), settings, scope, beforeSync = {
            editor.replayPending()
            NowPlaying.flushScrobbles(this)
            lyricsOffsets.sync()
        }, afterSync = {
            // Keep every playlist openable offline (see Library.warmPlaylists). Wi-Fi only.
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            if (status.online.value && !cm.isActiveNetworkMetered) library.warmPlaylists()
            bad.refresh()
        })
    }

    val player by lazy { PlayerConnection(app, this) }

    val live by lazy { LiveEvents(this, scope) }

    /** Seiðr Connect: other devices on this account, and which one is playing. */
    val connect by lazy { eu.bradshaw.seidr.connect.Connect(app, settings) { player.connect() } }

    /** The visualiser's settings, hidden presets and tuner memory. */
    val visPrefs by lazy { eu.bradshaw.seidr.vis.VisPrefs(app, user, scope) }

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph = instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext as Application).also { instance = it }
        }
    }
}
