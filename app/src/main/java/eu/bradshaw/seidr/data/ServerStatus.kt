package eu.bradshaw.seidr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope

/**
 * Whether the server can be reached right now. Combines the OS network state
 * with what the last API call saw, because a phone on mobile data without
 * Tailscale has a network but no server.
 */
class ServerStatus(scope: CoroutineScope) {
    private val _network = MutableStateFlow(true)
    private val _reachable = MutableStateFlow(true)

    val network: StateFlow<Boolean> = _network.asStateFlow()
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    /** True when tracks that aren't downloaded can be played. Drives the greyed-out offline mode. */
    val online: StateFlow<Boolean> = combine(_network, _reachable) { n, r -> n && r }
        .stateIn(scope, SharingStarted.Eagerly, true)

    fun setNetwork(available: Boolean) { _network.value = available }
    fun reportSuccess() { _reachable.value = true }
    fun reportFailure() { _reachable.value = false }
}
