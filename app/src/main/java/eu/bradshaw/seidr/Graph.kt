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
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired singletons. One per process, created on first use. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(app)
    val status = ServerStatus(scope)

    val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val client = SubsonicClient({ settings.server.value }, settings.coverSalt, baseHttp, ResponseStore(File(app.filesDir, "api")), status)
    val library = Library(client, settings.badReports, scope)
    val db = AppDatabase.build(app)
    val files = MusicFiles(app)
    val downloads = DownloadIndex(db.offline(), scope)

    val engine = SyncEngine(
        server = SubsonicSyncServer { client.strict },
        store = RoomSyncStore(db.offline()),
        files = files,
        protectedIds = { setOfNotNull(NowPlaying.currentSongId) },
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

    val bad = BadReports(settings, library, editor, db.offline(), scope) { client.strict }

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
    val visPrefs by lazy { eu.bradshaw.seidr.vis.VisPrefs(app) }

    companion object {
        @Volatile private var instance: Graph? = null

        fun get(context: Context): Graph = instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext as Application).also { instance = it }
        }
    }
}
