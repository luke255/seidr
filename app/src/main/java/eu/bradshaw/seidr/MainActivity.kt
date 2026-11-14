package eu.bradshaw.seidr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.bradshaw.seidr.ui.MainScreen
import eu.bradshaw.seidr.ui.Navigator
import eu.bradshaw.seidr.ui.Route
import eu.bradshaw.seidr.ui.Tab
import eu.bradshaw.seidr.ui.screens.LoginScreen
import eu.bradshaw.seidr.ui.screens.isBatteryExempt
import eu.bradshaw.seidr.ui.screens.requestBatteryExemption
import eu.bradshaw.seidr.ui.theme.SeidrTheme
import eu.bradshaw.seidr.ui.theme.ThemeChoice
import eu.bradshaw.seidr.update.Updater

class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()
    private val graph by lazy { Graph.get(this) }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Local network granted: a server on the LAN is reachable now, and Connect can start.
        if (result[Manifest.permission.ACCESS_LOCAL_NETWORK] == true) {
            graph.sync.request()
            graph.connect.onLanGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val theme by graph.settings.theme.collectAsStateWithLifecycle()
            val tv = remember { eu.bradshaw.seidr.ui.isTv(this) }
            androidx.compose.runtime.CompositionLocalProvider(eu.bradshaw.seidr.ui.LocalIsTv provides tv) {
            SeidrTheme(ThemeChoice.fromKey(theme)) {
                Surface(Modifier.fillMaxSize()) {
                    val server by graph.settings.server.collectAsStateWithLifecycle()
                    if (server == null) {
                        LoginScreen(graph)
                    } else {
                        MainScreen(graph, nav)
                        FirstRunPrompts()
                    }
                }
            }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun FirstRunPrompts() {
        // The prompt is about phone battery managers; TVs are on mains power.
        var showBattery by remember {
            mutableStateOf(!graph.settings.batteryPromptShown && !isBatteryExempt(this) && !eu.bradshaw.seidr.ui.isTv(this))
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val wanted = buildList {
                add(Manifest.permission.POST_NOTIFICATIONS)
                if (android.os.Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }
        if (showBattery) {
            AlertDialog(
                onDismissRequest = { showBattery = false; graph.settings.batteryPromptShown = true },
                title = { Text("Keep music playing") },
                text = { Text("Some phones' battery managers stop background playback and downloads. Let Seiðr run unrestricted? You can change this later in Settings.") },
                confirmButton = {
                    TextButton(onClick = {
                        showBattery = false
                        graph.settings.batteryPromptShown = true
                        requestBatteryExemption(this)
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { showBattery = false; graph.settings.batteryPromptShown = true }) { Text("Not now") }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        graph.player.connect()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        Updater.check(this)
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            graph.player.playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
            return
        }
        when (intent?.getStringExtra("open")) {
            "downloads" -> nav.open(Tab.LIBRARY, Route.Downloads)
            "nowplaying" -> nav.requestNowPlaying = true
        }
    }

    companion object {
        /** Resumed: an update offer or install confirmation can show now (Updater, UpdateReceiver). */
        @Volatile var inForeground = false
    }
}
