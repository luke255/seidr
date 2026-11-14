package eu.bradshaw.seidr.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import eu.bradshaw.seidr.MainActivity
import eu.bradshaw.seidr.sync.Notifications

/** PackageInstaller's verdicts on a self-update, plus clearing a stale "update ready" once it has landed. */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> Notifications.clearUpdate(context)
            Updater.ACTION_INSTALL_STATUS -> onStatus(context, intent)
        }
    }

    private fun onStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // In front: show the system's "Update this app?" straight away.
                // In the background an activity start would be blocked, so
                // leave a notification to tap instead.
                if (MainActivity.inForeground) {
                    context.startActivity(confirm)
                } else {
                    Notifications.updateReady(context, confirm, intent.getStringExtra(Updater.EXTRA_VERSION_NAME))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
            else -> Log.w(TAG, "update not installed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
    }
}
