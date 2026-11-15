package eu.bradshaw.seidr.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import eu.bradshaw.seidr.MainActivity
import eu.bradshaw.seidr.R

object Notifications {
    const val DOWNLOADS = "downloads"
    const val STORAGE = "storage"
    const val DOWNLOAD_ID = 2001
    const val STORAGE_ID = 2002
    const val UPDATES = "app-updates"
    const val UPDATE_ID = 2003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(DOWNLOADS, context.getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(STORAGE, context.getString(R.string.notif_channel_storage), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).putExtra("open", "downloads"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun downloading(context: Context, title: String, remaining: Int, progress: Int) =
        NotificationCompat.Builder(context, DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_downloading, remaining, remaining))
            .setContentText(title)
            .setProgress(100, progress, progress < 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .build()

    fun storageFull(context: Context, needMb: Long) {
        val n = NotificationCompat.Builder(context, STORAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_storage_title))
            .setContentText(context.getString(R.string.notif_storage_text, needMb))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(STORAGE_ID, n) }
    }

    fun clearStorageFull(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(STORAGE_ID)

    /** A self-update's install confirmation, for when it arrives with the app in the background. */
    fun updateReady(context: Context, confirm: Intent, versionName: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val tap = PendingIntent.getActivity(context, UPDATE_ID, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(versionName?.let { context.getString(R.string.update_ready_text, it) })
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(UPDATE_ID, n)
    }

    /** A leftover "update ready" is stale once the update has landed. */
    fun clearUpdate(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(UPDATE_ID)
}
