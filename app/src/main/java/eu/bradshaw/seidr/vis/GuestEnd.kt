package eu.bradshaw.seidr.vis

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.bradshaw.seidr.Graph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Signed out, a visualiser shows a guest's music: when they leave (Disconnect,
 * playback moving away, another user taking over), it closes, back to the
 * signed-out screen, rather than carrying on over nothing.
 */
fun ComponentActivity.closeWhenGuestLeaves(graph: Graph) {
    lifecycleScope.launch {
        var was = false
        combine(graph.settings.server, graph.guest) { server, guest -> server == null && guest != null }
            .distinctUntilChanged()
            .collect { playingForGuest ->
                if (was && !playingForGuest && !isFinishing) finish()
                was = playingForGuest
            }
    }
}
