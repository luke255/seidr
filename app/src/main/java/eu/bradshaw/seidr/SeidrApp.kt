package eu.bradshaw.seidr

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import eu.bradshaw.seidr.data.api.ok
import eu.bradshaw.seidr.sync.Notifications
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okio.Path.Companion.toOkioPath

/** Coil model for Subsonic cover art. Keyed by id and size so caching survives URL salts. */
data class CoverArt(val id: String, val size: Int)

class SeidrApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph

    override fun onCreate() {
        super.onCreate()
        // Before anything formats text outside composition (the service, the car).
        eu.bradshaw.seidr.ui.components.AppText.context = this
        graph = Graph.get(this)
        Notifications.createChannels(this)
        graph.scope.launch { graph.library.loadCachedPlaylists() }
        watchReachability()
        watchNetwork()

        // Sync whenever the app comes to the foreground (which includes app start).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.connect.startDiscovery()
                graph.presetPack.downloadIfUnmetered()
                if (graph.settings.server.value == null) return
                graph.scope.launch {
                    probeServer()
                    graph.loudness.refresh()
                    graph.library.refreshStarred()
                    graph.bad.refresh()
                }
                graph.sync.request()
                graph.live.start()
            }

            // Live updates only while on screen: an open connection in the background costs battery.
            override fun onStop(owner: LifecycleOwner) {
                graph.live.stop()
                graph.connect.stopDiscovery()
            }
        })
        if (graph.settings.server.value != null) graph.sync.schedulePeriodic()
    }

    /**
     * Checks the server is reachable now, updating offline mode either way.
     * Calls made while a probe is running are folded into one follow-up probe,
     * so the latest network state is always the one checked.
     */
    suspend fun probeServer() {
        probeAgain = true
        while (probeAgain && probeLock.tryLock()) {
            try {
                while (probeAgain) {
                    probeAgain = false
                    val ok = runCatching { graph.client.strict.ping().ok() }.isSuccess
                    Log.i("Status", "probe: reachable=$ok")
                }
            } finally {
                probeLock.unlock()
            }
        }
    }

    private val probeLock = Mutex()
    @Volatile private var probeAgain = false

    /** Network changes arrive in bursts (Wi-Fi teardown, VPN re-homing); probe once they settle. */
    private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    private fun watchReachability() {
        graph.scope.launch {
            networkChanged.debounce(1_500).collect {
                if (graph.settings.server.value == null && graph.guest.value == null) return@collect
                val wasOnline = graph.status.online.value
                probeServer()
                // Back online: catch up on anything missed.
                if (!wasOnline && graph.status.online.value) graph.sync.request()
            }
        }
        // While the server is unreachable, keep checking: it can come back without any
        // network change we'd hear about (server restart, Tailscale reconnecting).
        graph.scope.launch {
            graph.status.reachable.collectLatest { reachable ->
                while (!reachable && (graph.settings.server.value != null || graph.guest.value != null)) {
                    delay(30_000)
                    if (graph.status.network.value) networkChanged.tryEmit(Unit)
                }
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        graph.status.setNetwork(cm.activeNetwork != null)
        // Wi-Fi coming and going, seen directly: behind a VPN the default network hides it.
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { networkChanged.tryEmit(Unit) }
                override fun onLost(network: Network) { networkChanged.tryEmit(Unit) }
            },
        )
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                graph.status.setNetwork(true)
                networkChanged.tryEmit(Unit)
            }

            private var lastShape: String? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Wi-Fi <-> mobile can change server reachability (LAN vs Tailscale), and with a
                // VPN up the default network never changes; only its capabilities do. Ignore
                // the rest (signal strength updates arrive every few seconds).
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
                ).filter(caps::hasTransport)
                val shape = "$network $transports ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
                if (shape != lastShape) { lastShape = shape; networkChanged.tryEmit(Unit) }
            }

            override fun onLost(network: Network) {
                graph.status.setNetwork(cm.activeNetwork != null && cm.activeNetwork != network)
                networkChanged.tryEmit(Unit)
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(Mapper<CoverArt, Any> { data, _ ->
                    // Prefer cover art saved alongside downloads, so offline mode has pictures.
                    graph.files.coverFile(data.id).takeIf { it.isFile }
                        ?: graph.client.coverArtUrl(data.id, data.size)
                })
                add(Keyer<CoverArt> { data, _: Options -> "cover:${data.id}:${data.size}" })
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.baseHttp }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
