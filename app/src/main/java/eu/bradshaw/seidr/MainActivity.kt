package eu.bradshaw.seidr

import androidx.compose.ui.res.stringResource
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
import eu.bradshaw.seidr.ui.screens.SignedOutScreen
import eu.bradshaw.seidr.ui.screens.isBatteryExempt
import eu.bradshaw.seidr.ui.screens.requestBatteryExemption
import eu.bradshaw.seidr.ui.theme.SeidrTheme
import eu.bradshaw.seidr.ui.theme.ThemeChoice
import eu.bradshaw.seidr.update.Updater
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
        // Browsing as a kid starts or ends: every tab back to its root, so no screen
        // keeps what it loaded from the other library. Not on recreation (unfolding).
        lifecycleScope.launch {
            graph.kidView.collect { if (nav.kidView !== it) { nav.kidView = it; nav.reset() } }
        }
        setContent {
            val server by graph.settings.server.collectAsStateWithLifecycle()
            // Playing for someone else: their colour.
            val theme by graph.theme.collectAsStateWithLifecycle()
            val tv = remember { eu.bradshaw.seidr.ui.isTv(this) }
            androidx.compose.runtime.CompositionLocalProvider(eu.bradshaw.seidr.ui.LocalIsTv provides tv) {
            SeidrTheme(theme) {
                Surface(Modifier.fillMaxSize()) {
                    AskPermissions()
                    if (server == null) {
                        SignedOutScreen(graph, nav)
                    } else {
                        // Browsing as a kid starts or ends: every screen comes back fresh, reading the other library.
                        val view by graph.kidView.collectAsStateWithLifecycle()
                        androidx.compose.runtime.key(view) { MainScreen(graph, nav) }
                        FirstRunPrompts()
                    }
                }
            }
            }
        }
    }

    /** Notifications, and (Android 17) Nearby devices, which Connect needs signed in or out. */
    @androidx.compose.runtime.Composable
    private fun AskPermissions() {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val wanted = buildList {
                add(Manifest.permission.POST_NOTIFICATIONS)
                if (android.os.Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        }
    }

    @androidx.compose.runtime.Composable
    private fun FirstRunPrompts() {
        // The prompt is about phone battery managers; TVs are on mains power.
        var showBattery by remember {
            mutableStateOf(!graph.settings.batteryPromptShown && !isBatteryExempt(this) && !eu.bradshaw.seidr.ui.isTv(this))
        }
        if (showBattery) {
            AlertDialog(
                onDismissRequest = { showBattery = false; graph.settings.batteryPromptShown = true },
                title = { Text(stringResource(R.string.main_battery_title)) },
                text = { Text(stringResource(R.string.main_battery_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        showBattery = false
                        graph.settings.batteryPromptShown = true
                        requestBatteryExemption(this)
                    }) { Text(stringResource(R.string.main_battery_allow)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBattery = false; graph.settings.batteryPromptShown = true }) { Text(stringResource(R.string.vis_not_now)) }
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
