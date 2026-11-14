package eu.bradshaw.seidr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.bradshaw.seidr.data.Account
import eu.bradshaw.seidr.data.KidLogin
import eu.bradshaw.seidr.data.ServerConfig
import eu.bradshaw.seidr.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Debug builds only: lets `scripts/debug-account.sh` sign in, or add a kid,
 * through the same code as the screens, without typing into them (adb text
 * input into password fields drops characters). The login arrives as a file
 * in app-private storage, never on a command line, and is deleted on read.
 * Each outcome is logged under the tag [TAG].
 */
class DebugAccountReceiver : BroadcastReceiver() {
    @Serializable
    private data class Request(
        val cmd: String,
        val url: String? = null,
        val username: String,
        val password: String,
        val kidOf: String? = null,
    )

    override fun onReceive(context: Context, intent: Intent) {
        val file = File(context.filesDir, FILE)
        val request = runCatching { Json.decodeFromString<Request>(file.readText()) }
            .also { file.delete() }
            .getOrElse { Log.w(TAG, "FAILED: no readable $FILE: $it"); return }
        val graph = Graph.get(context)
        val pending = goAsync()
        graph.scope.launch {
            val result = runCatching { handle(graph, context, request) }.getOrElse { "FAILED: $it" }
            Log.i(TAG, result)
            pending.finish()
        }
    }

    private suspend fun handle(graph: Graph, context: Context, r: Request): String = when (r.cmd) {
        "signin" -> {
            val config = ServerConfig(Settings.normaliseUrl(r.url ?: return "FAILED: signin needs a url"),
                r.username.trim(), r.password, r.kidOf?.trim()?.takeIf { it.isNotEmpty() })
            Account.probe(graph, context, config)?.let { "FAILED: $it" } ?: run {
                withContext(Dispatchers.Main) { Account.commit(graph, config) }
                "OK: signed in as ${config.username}" + (config.kidOf?.let { " (kid of $it)" } ?: "")
            }
        }
        "add-kid" -> {
            val own = graph.settings.server.value ?: return "FAILED: not signed in"
            if (own.isKid) return "FAILED: signed in as a kid"
            Account.probeKid(graph, context, ServerConfig(own.url, r.username.trim(), r.password))?.let { "FAILED: $it" } ?: run {
                graph.settings.saveKid(KidLogin(r.username.trim(), r.password))
                "OK: added ${r.username.trim()}" + (graph.kids.ensureLists()?.let { "; $it" } ?: "")
            }
        }
        else -> "FAILED: unknown command ${r.cmd}"
    }

    companion object {
        const val TAG = "DebugAccount"
        const val FILE = "debug-account.json"
    }
}
