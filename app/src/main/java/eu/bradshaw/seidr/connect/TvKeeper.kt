package eu.bradshaw.seidr.connect

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.sync.Notifications
import eu.bradshaw.seidr.ui.isTv

/**
 * Android TV only: keeps Seiðr ready to play from the moment the TV starts.
 *
 * In the background Android caches and soon freezes the app's process, and a
 * frozen Connect can't answer: the TV would vanish from other devices' Play on
 * whenever Seiðr wasn't open. This foreground service ("connected device")
 * keeps the process out of the freezer, and binds PlaybackService, where
 * [ConnectSession] lives, so the TV is always listed and always answers. It
 * also keeps the TV's own Cast entry out of Play on (Connect lists a Cast
 * device at a Seiðr device's address once, as Seiðr).
 *
 * Started at boot, after an update, and whenever the app starts. Idle, it's a
 * socket and an mDNS advert.
 */
class TvKeeper : Service() {
    override fun onCreate() {
        super.onCreate()
        val n = NotificationCompat.Builder(this, Notifications.READY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.login_idle_ready))
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(Notifications.READY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Graph.get(this).player.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?) = null

    companion object {
        fun start(context: Context) {
            if (!isTv(context)) return
            // Refused when the process starts in the background; the boot or update receiver starts it then.
            runCatching { context.startForegroundService(Intent(context, TvKeeper::class.java)) }
                .onFailure { Log.i(Connect.TAG, "Connect not kept ready yet: $it") }
        }
    }
}

/** Starts [TvKeeper] when the TV boots and after Seiðr updates. */
class TvKeeperStarter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TvKeeper.start(context)
        }
    }
}
