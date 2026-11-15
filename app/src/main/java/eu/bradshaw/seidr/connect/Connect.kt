package eu.bradshaw.seidr.connect

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import eu.bradshaw.seidr.data.KidLogin
import eu.bradshaw.seidr.data.Secrets
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.Settings
import eu.bradshaw.seidr.ui.isTv
import eu.bradshaw.seidr.ui.pair.deviceName
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.UUID

/**
 * A login a signed-out device is playing for, sent by the device controlling
 * it. In memory only: gone on disconnect, takeover or restart. [key] is its
 * [ConnectKeys.loginId], to tell a second controller of the same user from a
 * takeover; [from] names the sending device; [theme] is its colour.
 */
class GuestLogin(val config: ServerConfig, val key: ByteArray, val from: String, val theme: String?)

/**
 * Seiðr Connect, app side: who this device is, which other devices are on
 * the network (mDNS: same account, plus signed-out ones), and which one is
 * playing. The work of playing on or controlling another device is
 * [ConnectSession]'s, which lives in PlaybackService and registers itself here.
 */
class Connect(
    private val app: Application,
    private val settings: Settings,
    private val startService: () -> Unit,
) {
    private val prefs = app.getSharedPreferences("connect", Context.MODE_PRIVATE)
    private val secrets = Secrets(prefs)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Stable per install. */
    val deviceId: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }

    val name: String get() = deviceName(app).trim()

    val kind: String = when {
        isTv(app) -> Peer.KIND_TV
        XrVisualiserActivity.available(app) -> Peer.KIND_HEADSET
        else -> Peer.KIND_PHONE
    }

    /** The account key: signed out, being derived (slow on purpose), or ready. */
    private sealed interface AccountKey {
        data object SignedOut : AccountKey
        data object Deriving : AccountKey
        class Ready(val key: ByteArray) : AccountKey
    }
    private val keyState = MutableStateFlow(
        settings.server.value?.let { s -> storedKey(s)?.let(AccountKey::Ready) ?: AccountKey.Deriving } ?: AccountKey.SignedOut,
    )

    /**
     * The key derived for this login before, kept encrypted ([Secrets]) with
     * the login's id: deriving takes ~5 s on a slow TV, so it's done once per
     * sign-in, not on every start.
     */
    private fun storedKey(s: ServerConfig): ByteArray? {
        val (id, key) = secrets.read("account_key")?.split(':')?.takeIf { it.size == 2 } ?: return null
        if (id != b64(ConnectKeys.loginId(s.username, s.password))) return null
        return runCatching { Base64.decode(key, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Null while signed out, and while the key is still being derived after a sign-in. */
    val accountKey: ByteArray? get() = (keyState.value as? AccountKey.Ready)?.key

    /** [accountKey], waiting out a derivation in progress. Null when signed out. */
    suspend fun awaitAccountKey(): ByteArray? =
        (keyState.first { it != AccountKey.Deriving } as? AccountKey.Ready)?.key

    init {
        // PBKDF2 takes up to a second on a slow TV: never on the main thread, once per login.
        // Dispatched (not immediate), so it starts after the rest of this class is set up.
        main.launch(Dispatchers.Main) {
            settings.server.collectLatest { s ->
                if (s == null) {
                    keyState.value = AccountKey.SignedOut
                    secrets.write("account_key", null)
                } else {
                    val stored = storedKey(s)
                    if (stored != null) {
                        keyState.value = AccountKey.Ready(stored)
                    } else {
                        keyState.value = AccountKey.Deriving
                        val started = android.os.SystemClock.elapsedRealtime()
                        val key = withContext(Dispatchers.Default) { ConnectKeys.account(s.username, s.password) }
                        Log.i(TAG, "account key derived in ${android.os.SystemClock.elapsedRealtime() - started} ms")
                        secrets.write("account_key", b64(ConnectKeys.loginId(s.username, s.password)) + ":" + b64(key))
                        keyState.value = AccountKey.Ready(key)
                    }
                }
                // Advertising and discovery wait for the key; catch them up.
                if (wantedPort > 0) advertise(wantedPort)
                if (discoveryWanted) startDiscovery()
            }
        }
    }

    /** This install's long-term key, which signed-in devices pin once its code is checked. */
    val identity: KeyPair by lazy {
        val f = KeyFactory.getInstance("EC")
        runCatching {
            val pub = Base64.decode(prefs.getString("identity_pub", null)!!, Base64.NO_WRAP)
            val priv = Base64.decode(secrets.read("identity_priv")!!, Base64.NO_WRAP)
            KeyPair(f.generatePublic(X509EncodedKeySpec(pub)), f.generatePrivate(PKCS8EncodedKeySpec(priv)))
        }.getOrElse {
            ConnectKeys.newKeyPair().also {
                prefs.edit().putString("identity_pub", Base64.encodeToString(it.public.encoded, Base64.NO_WRAP)).apply()
                secrets.write("identity_priv", Base64.encodeToString(it.private.encoded, Base64.NO_WRAP))
            }
        }
    }

    private fun isPinned(hostKey: ByteArray) = ConnectKeys.fingerprint(hostKey) in prefs.getStringSet("pinned", emptySet()).orEmpty()

    private fun pin(hostKey: ByteArray) {
        val pinned = prefs.getStringSet("pinned", emptySet()).orEmpty()
        prefs.edit().putStringSet("pinned", pinned + ConnectKeys.fingerprint(hostKey)).apply()
    }

    private val _devices = MutableStateFlow<List<Peer>>(emptyList())
    /** Other devices on the same network: signed in as this user, or signed out ([Peer.guest]). */
    val devices: StateFlow<List<Peer>> = _devices.asStateFlow()

    internal val _active = MutableStateFlow<Peer?>(null)
    /** The device this one is controlling; null while playback is here. */
    val active: StateFlow<Peer?> = _active.asStateFlow()

    internal val _connecting = MutableStateFlow<String?>(null)
    /** Id of a device being switched to. */
    val connecting: StateFlow<String?> = _connecting.asStateFlow()

    /** A code to compare before trusting a signed-out device; the UI shows it and answers. */
    /** [signingInAs]: the account a sign-in hands over (a kid's, or null for this device's own). */
    class Check(val peer: Peer, val code: String, val signingIn: Boolean, val signingInAs: String? = null) {
        internal val answer = CompletableDeferred<Boolean>()
        fun confirm() { answer.complete(true) }
        fun cancel() { answer.complete(false) }
    }

    private val _check = MutableStateFlow<Check?>(null)
    /** Controller side: a code waiting for the user to compare it with the other screen. */
    val check: StateFlow<Check?> = _check.asStateFlow()

    /** The code this (signed-out) device is showing, and who to compare it with. */
    data class ShownCode(val code: String, val from: String, internal val token: Any = Any())

    internal val _shown = MutableStateFlow<ShownCode?>(null)
    /** Host side: a code to show while a controller checks it. */
    val shown: StateFlow<ShownCode?> = _shown.asStateFlow()

    /**
     * Host side, signed out: another device wants to sign this one in. Nothing
     * is saved (or even tried) until someone here says yes.
     */
    class SignInRequest(val from: String, val username: String, val server: String, val kid: Boolean) {
        internal val answer = CompletableDeferred<Boolean>()
        fun allow() { answer.complete(true) }
        fun deny() { answer.complete(false) }
    }

    internal val _signInRequest = MutableStateFlow<SignInRequest?>(null)
    /** A sign-in waiting for this device's answer. */
    val signInRequest: StateFlow<SignInRequest?> = _signInRequest.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Things to tell the user ("Lost connection to …"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    internal fun tell(message: String) { _messages.tryEmit(message) }

    @Volatile internal var session: ConnectSession? = null
        set(value) { field = value; sessionUp.value = value != null }
    private val sessionUp = MutableStateFlow(false)

    /** Signed-out side: stops playing for whoever sent music here, and forgets their login. */
    fun disconnectGuest() {
        main.launch { session?.endGuest("${name} disconnected") }
    }

    sealed interface SignInResult {
        data object Done : SignInResult
        data object Cancelled : SignInResult
        data class Failed(val message: String) : SignInResult
    }

    /**
     * Signs a signed-out [peer] in, once its code checks out: with this
     * device's login, or [kid]'s (a kid's login stays a kid's either way).
     */
    suspend fun signIn(peer: Peer, kid: KidLogin? = null): SignInResult {
        val own = settings.server.value ?: return SignInResult.Failed("This device isn't signed in")
        val config = if (kid == null) own else ServerConfig(own.url, kid.username, kid.password, kidOf = own.username)
        val d = try {
            dial(peer, ask = true, signingIn = true, signingInAs = kid?.username) ?: return SignInResult.Cancelled
        } catch (e: IOException) {
            return SignInResult.Failed(dialError(peer, e))
        }
        if (config.isKid && d.hello.version < KIDS_VERSION) {
            d.ch.close()
            return SignInResult.Failed("Update Seiðr on ${peer.name} first: this version can't keep a kid's login safe")
        }
        return withContext(Dispatchers.IO) {
            try {
                d.ch.send(ConnectMessage.SignIn(config.url, config.username, config.password, config.kidOf))
                // Someone there allows it, then it pings the server, then it answers.
                d.socket.soTimeout = SIGN_IN_TIMEOUT_MS
                when (val r = d.ch.receive()) {
                    is ConnectMessage.SignedIn -> r.error?.let { SignInResult.Failed(it) } ?: SignInResult.Done
                    else -> SignInResult.Failed("${peer.name} didn't answer")
                }
            } catch (e: IOException) {
                SignInResult.Failed("Lost ${peer.name}: ${e.message}")
            } finally {
                d.ch.close()
            }
        }
    }

    /** A channel to another device, greeted. */
    internal class Dialled(val socket: Socket, val ch: ConnectChannel, val hello: ConnectMessage.Hello)

    /**
     * Connects to [peer] and says hello. A signed-out device's key must be
     * pinned, or (with [ask]) checked by code now, as it always is when
     * [signingIn]; null if the user cancels.
     * Throws IOException when it can't get through.
     */
    internal suspend fun dial(peer: Peer, ask: Boolean, signingIn: Boolean = false, signingInAs: String? = null): Dialled? {
        val account = awaitAccountKey() ?: throw IOException("not signed in")
        val (socket, ch, hello) = withContext(Dispatchers.IO) {
            val socket = connectTo(peer) ?: throw IOException("unreachable")
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val ch = if (peer.guest) ConnectChannel.dialGuest(socket) else ConnectChannel.open(socket, account, isHost = false)
                // Signing in always shows the code: it leaves the password there for good.
                val check = ch.guest?.let { signingIn || !isPinned(it.hostKey) } ?: false
                if (check && !ask) throw IOException("not paired")
                ch.send(ConnectMessage.Hello(deviceId, name, verify = check))
                val hello = ch.receive() as? ConnectMessage.Hello ?: throw IOException("Expected hello")
                Triple(socket, ch, hello)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }
        val g = ch.guest
        if (g != null && (signingIn || !isPinned(g.hostKey))) {
            val c = Check(peer, g.code, signingIn, signingInAs)
            _check.value = c
            val ok = try {
                withTimeoutOrNull(CHECK_TIMEOUT_MS) { c.answer.await() } ?: false
            } finally {
                if (_check.value === c) _check.value = null
            }
            if (!ok) { ch.close(); return null }
            pin(g.hostKey)
        }
        return Dialled(socket, ch, hello)
    }

    /** What to tell the user when [dial] fails. */
    internal fun dialError(peer: Peer, e: IOException): String = when (e) {
        is WrongAccountException -> "${peer.name} is signed in as someone else"
        is SignedInOrOutException -> "${peer.name} has signed in or out. Try again in a moment."
        else -> "Couldn't reach ${peer.name}. Is Seiðr open on it?"
    }

    private fun connectTo(peer: Peer): Socket? {
        for (host in peer.hosts) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, peer.port), CONNECT_TIMEOUT_MS)
                return s
            } catch (_: IOException) {
                runCatching { s.close() }
            }
        }
        return null
    }

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

    /**
     * Announces this device's server on [port]: tagged with the account, or
     * signed out as a guest anyone can use. Again after a sign-in or out.
     */
    fun advertise(port: Int) {
        unregister()
        wantedPort = port
        if (!lanAllowed()) return
        // Not as a guest while a sign-in's key is still being derived: init advertises once it's ready.
        if (keyState.value == AccountKey.Deriving) return
        val key = accountKey
        val info = NsdServiceInfo().apply {
            serviceName = "Seiðr ${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", deviceId)
            setAttribute("n", name.trim().take(60))
            setAttribute("k", kind)
            if (key != null) setAttribute("a", ConnectKeys.tag(key)) else setAttribute("g", "1")
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

    /** Stops advertising for good (the service is going). */
    fun unadvertise() {
        wantedPort = 0
        unregister()
    }

    private fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    // --- Discovery (while the app is on screen) ------------------------------------

    private var discovery: NsdManager.DiscoveryListener? = null
    /** Per service name: the resolver watching it, and the device it resolved to. */
    private val watching = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val found = mutableMapOf<String, Peer>()

    /** Discovery was asked for and not stopped: start it once the account key is ready. */
    private var discoveryWanted = false

    fun startDiscovery() = main.launch {
        discoveryWanted = true
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
        discoveryWanted = false
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
        val isGuest = attr("g") == "1"
        if (id == null || id == deviceId || key == null || (!isGuest && attr("a") != ConnectKeys.tag(key)) || hosts.isEmpty() || info.port <= 0) {
            found.remove(name)
        } else {
            found[name] = Peer(id, attr("n").orEmpty().ifBlank { "Seiðr device" }, hosts, info.port, attr("k") ?: Peer.KIND_PHONE, isGuest)
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
        internal const val CONNECT_TIMEOUT_MS = 3_000
        /** Three missed heartbeats. */
        internal const val READ_TIMEOUT_MS = 15_000
        /** Long enough for the other device to ping the server (8 s connect, a slow reply). */
        /** How long a signed-out device waits for someone to allow a sign-in. */
        internal const val ASK_TIMEOUT_MS = 90_000L
        /** A controller from before the prompt gives up after 30 s; answer it sooner. */
        internal const val OLD_ASK_TIMEOUT_MS = 20_000L
        /** The host's prompt, then its server ping. */
        private const val SIGN_IN_TIMEOUT_MS = (ASK_TIMEOUT_MS + 30_000).toInt()
        /** How long the code waits for someone to compare it. */
        internal const val CHECK_TIMEOUT_MS = 120_000L
    }
}

/**
 * IPv4 addresses another device could reach: private LAN ranges plus
 * 100.64/10 (Tailscale). Wi-Fi and Ethernet first; cellular interfaces skipped.
 */
fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !CELLULAR.any(it.name::startsWith) }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, a) -> a.isSiteLocalAddress || isCgnat(a) }
        .sortedBy { (name, _) -> if (name.startsWith("wlan") || name.startsWith("eth")) 0 else 1 }
        .mapNotNull { it.second.hostAddress }
        .distinct()
}.getOrDefault(emptyList())

private fun isCgnat(a: Inet4Address): Boolean {
    val b = a.address
    return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
}

private val CELLULAR = listOf("rmnet", "ccmni", "clat", "v4-", "dummy")
