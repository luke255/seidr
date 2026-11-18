package eu.bradshaw.seidr.cast

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import eu.bradshaw.seidr.connect.Peer
import java.net.Inet4Address
import java.util.concurrent.Executor

/**
 * Finds Cast devices (`_googlecast._tcp`): speakers, speaker groups and
 * TVs. [onChange] gets the full list each time it changes, on [executor].
 * Callers check the Nearby devices permission first, as for Connect.
 */
class CastDiscovery(private val nsd: NsdManager, private val executor: Executor, private val onChange: (List<Peer>) -> Unit) {
    private var listener: NsdManager.DiscoveryListener? = null
    private val watching = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val found = mutableMapOf<String, Peer>()

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                executor.execute { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { executor.execute { watch(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { executor.execute { forget(info.serviceName) } }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discovery failed", it); listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        watching.keys.toList().forEach(::forget)
        found.clear()
        publish()
    }

    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name in watching) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(error: Int) { executor.execute { watching.remove(name) } }
            override fun onServiceUpdated(info: NsdServiceInfo) { executor.execute { resolved(name, info) } }
            override fun onServiceLost() { executor.execute { found.remove(name); publish() } }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        watching[name] = callback
        runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure { watching.remove(name) }
    }

    private fun resolved(name: String, info: NsdServiceInfo) {
        if (name !in watching) return
        fun attr(k: String) = info.attributes[k]?.decodeToString()
        val hosts = info.hostAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
        val id = attr("id")
        val friendly = attr("fn")?.trim()
        if (id == null || friendly.isNullOrEmpty() || hosts.isEmpty() || info.port <= 0) found.remove(name)
        else found[name] = peer(id, friendly, hosts, info.port, attr("md"), attr("ca")?.toIntOrNull())
        publish()
    }

    private fun forget(name: String) {
        watching.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        found.remove(name)
        publish()
    }

    private fun publish() = onChange(found.values.distinctBy { it.id })

    companion object {
        const val SERVICE_TYPE = "_googlecast._tcp"
        const val DEFAULT_PORT = 8009
        private const val TAG = "Cast"

        /** The Cast capability bit for a screen. */
        private const val VIDEO_OUT = 1

        fun peer(id: String, name: String, hosts: List<String>, port: Int, model: String?, capabilities: Int?) = Peer(
            id = "cast:$id",
            name = name,
            hosts = hosts,
            port = port,
            kind = when {
                model == "Google Cast Group" -> Peer.KIND_SPEAKER_GROUP
                capabilities != null && capabilities and VIDEO_OUT != 0 -> Peer.KIND_TV
                else -> Peer.KIND_SPEAKER
            },
            cast = true,
        )

    }
}
