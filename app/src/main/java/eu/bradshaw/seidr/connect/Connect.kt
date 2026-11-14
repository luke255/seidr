package eu.bradshaw.seidr.connect

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.ui.pair.deviceName
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.UUID

/**
 * Seiðr Connect, app side: who this device is, which other devices are on
 * the network (mDNS, same account only), and which one is playing. The work
 * of playing on or controlling another device is [ConnectSession]'s, which
 * lives in PlaybackService and registers itself here.
 */
class Connect(private val app: Application, private val settings: Settings, private val startService: () -> Unit) {
    private val prefs = app.getSharedPreferences("connect", Context.MODE_PRIVATE)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Stable per install. */
    val deviceId: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    val name: String get() = deviceName(app)

    val kind: String = when {
        isTv(app) -> Peer.KIND_TV
        XrVisualiserActivity.available(app) -> Peer.KIND_HEADSET
        else -> Peer.KIND_PHONE
    }

    /** Null while signed out: no Connect then. */
    val accountKey: ByteArray? get() = settings.server.value?.let { ConnectKeys.account(it.username, it.password) }

    private val _devices = MutableStateFlow<List<Peer>>(emptyList())
    /** Other devices signed in as this user on the same network. */
    val devices: StateFlow<List<Peer>> = _devices.asStateFlow()

    internal val _active = MutableStateFlow<Peer?>(null)
    /** The device this one is controlling; null while playback is here. */
    val active: StateFlow<Peer?> = _active.asStateFlow()

    internal val _connecting = MutableStateFlow<String?>(null)
    /** Id of a device being switched to. */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Things to tell the user ("Lost connection to …"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    internal fun tell(message: String) { _messages.tryEmit(message) }

    @Volatile internal var session: ConnectSession? = null
        set(value) { field = value; sessionUp.value = value != null }
    private val sessionUp = MutableStateFlow(false)

    /** Moves playback to [peer], or back here when it's null (or this device). */
    fun playOn(peer: Peer?) {
        main.launch {
            val s = session ?: run {
                startService()
                withTimeoutOrNull(5_000) { sessionUp.first { it } }
                session
            } ?: return@launch tell("Playback isn't ready yet")
            s.playOn(peer?.takeIf { it.id != deviceId })
        }
    }

    // --- Advertising ----------------------------------------------------------------

    private val nsd = app.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    /**
     * Android 17 shows a system device picker to apps that use mDNS without
     * "Nearby devices" (ACCESS_LOCAL_NETWORK). Connect waits for the
     * permission instead (asked on first run), and for a sign-in.
     */
    private fun lanAllowed(): Boolean = android.os.Build.VERSION.SDK_INT < 37 ||
        app.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The port last asked to be advertised, for [onLanGranted]. */
    private var wantedPort = 0

    /** "Nearby devices" was just granted: start what [lanAllowed] held back. */
    fun onLanGranted() {
        if (wantedPort > 0) advertise(wantedPort)
        startDiscovery()
    }

    /** Announces this device's server on [port]. Again after a sign-in, since the tag changes. */
    fun advertise(port: Int) {
        unadvertise()
        wantedPort = port
        if (!lanAllowed()) return
        val key = accountKey ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Seiðr ${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("n", name.trim().take(60))
            setAttribute("k", kind)
            setAttribute("a", ConnectKeys.tag(key))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "advertised as ${info.serviceName} on $port") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.w(TAG, "advertising failed: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "advertising failed", it); registration = null }
    }

    fun unadvertise() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    // --- Discovery (while the app is on screen) ------------------------------------

    private var discovery: NsdManager.DiscoveryListener? = null
    /** Per service name: the resolver watching it, and the device it resolved to. */
    private val watching = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val found = mutableMapOf<String, Peer>()

    fun startDiscovery() = main.launch {
        if (discovery != null || accountKey == null || !lanAllowed()) return@launch
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                main.launch { discovery = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.launch { watch(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.launch { forget(info.serviceName) } }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discovery failed", it); discovery = null }
    }

    fun stopDiscovery() = main.launch {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        watching.keys.toList().forEach(::forget)
    }

    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name in watching) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(error: Int) { main.launch { watching.remove(name) } }
            override fun onServiceUpdated(info: NsdServiceInfo) { main.launch { resolved(name, info) } }
            override fun onServiceLost() { main.launch { found.remove(name); publish() } }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        watching[name] = callback
        runCatching { nsd.registerServiceInfoCallback(info, app.mainExecutor, callback) }
            .onFailure { watching.remove(name) }
    }

    private fun resolved(name: String, info: NsdServiceInfo) {
        if (name !in watching) return
        val attrs = info.attributes
        fun attr(k: String) = attrs[k]?.decodeToString()
        val id = attr("id")
        val key = accountKey
        val hosts = info.hostAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }.mapNotNull { it.hostAddress?.substringBefore('%') }
        if (id == null || id == deviceId || key == null || attr("a") != ConnectKeys.tag(key) || hosts.isEmpty() || info.port <= 0) {
            found.remove(name)
        } else {
            found[name] = Peer(id, attr("n").orEmpty().ifBlank { "Seiðr device" }, hosts, info.port, attr("k") ?: Peer.KIND_PHONE)
        }
        publish()
    }

    private fun forget(name: String) {
        watching.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        found.remove(name)
        publish()
    }

    private fun publish() {
        _devices.value = found.values.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    companion object {
        const val SERVICE_TYPE = "_seidr._tcp"
        internal const val TAG = "Connect"
    }
}
