package eu.bradshaw.seidr.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import eu.bradshaw.seidr.BuildConfig
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.MainActivity
import eu.bradshaw.seidr.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Self-update from GitHub Releases. UPDATE_URL (app/build.gradle.kts) names
 * the latest release's version.json, which `scripts/release.sh` uploads with
 * the APK. When its versionCode is newer than ours, the APK is downloaded,
 * checked against its sha256, and offered in a dialog. Only "Install" hands it
 * to PackageInstaller, so Play Protect's scan never appears unannounced. The
 * system may ask once more (see UpdateReceiver) and checks the signature
 * matches the installed app.
 *
 * Automatic checks can be turned off in Settings (`Settings.autoUpdate`);
 * Settings → Check for updates always works.
 */
object Updater {
    const val ACTION_INSTALL_STATUS = "eu.bradshaw.seidr.INSTALL_STATUS"
    const val EXTRA_VERSION_NAME = "versionName"

    private const val TAG = "Updater"
    private const val THROTTLE_MS = 30 * 60_000L

    /**
     * The newest version the last check found, or null. Shown as a star on the
     * Settings icon. Never cleared: installing it restarts the process.
     */
    val available = MutableStateFlow<String?>(null)

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastCheck = 0L
    private val main = Handler(Looper.getMainLooper())

    /** A downloaded, verified update waiting for the user's answer. On disk, not in memory: the APK is large. */
    private class Ready(val code: Long, val versionName: String, val apk: File, val notes: String?)

    // Main thread only.
    private var ready: Ready? = null
    private var declined = 0L // "Later" holds until a newer version or a new process
    private var host: WeakReference<Activity>? = null
    private var dialog: AlertDialog? = null
    private var refreshing = false

    /**
     * Cheap to call on every resume: offers a waiting update, otherwise at
     * most one check per 30 minutes per process.
     */
    fun check(activity: Activity) {
        if (BuildConfig.UPDATE_URL.isEmpty()) return
        if (!Graph.get(activity).settings.autoUpdate.value) return
        host = WeakReference(activity)
        ready?.let { offer(activity, it); return }
        val now = SystemClock.elapsedRealtime()
        if (busy || (lastCheck != 0L && now - lastCheck < THROTTLE_MS)) return
        lastCheck = now
        busy = true
        val app = activity.applicationContext
        executor.execute {
            try {
                run(app)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e") // offline is normal; the next resume tries again
            } finally {
                busy = false
            }
        }
    }

    private fun run(context: Context) {
        val update = fetch(context, skip = declined) ?: return
        main.post {
            if (refreshing) return@post // Settings' check is installing it already
            ready = update
            // Backgrounded meanwhile: the next resume offers it.
            host?.get()?.takeIf { MainActivity.inForeground && !it.isFinishing }?.let { offer(it, update) }
        }
    }

    /**
     * Settings → "Check for updates": checks now, whatever the
     * throttle or an earlier "Later", and installs a newer version straight
     * away, since the user just asked for it. [done] gets the version being
     * installed, or null when there's nothing newer or the check failed.
     */
    fun refresh(activity: Activity, done: (String?) -> Unit) {
        if (BuildConfig.UPDATE_URL.isEmpty()) return done(null)
        host = WeakReference(activity)
        refreshing = true
        val waiting = ready
        ready = null
        dialog?.dismiss()
        lastCheck = SystemClock.elapsedRealtime()
        val app = activity.applicationContext
        // The single-thread executor runs this after any check in flight.
        executor.execute {
            val update = try {
                waiting ?: fetch(app, skip = 0L)?.also { Log.i(TAG, "refresh: installing ${it.versionName}") }
            } catch (e: Exception) {
                Log.w(TAG, "refresh: update check failed: $e")
                null
            }
            val installing = update != null && try {
                install(app, update.apk, update.versionName)
                true
            } catch (e: Exception) {
                Log.w(TAG, "refresh: install failed: $e")
                false
            }
            main.post {
                refreshing = false
                done(if (installing) update?.versionName else null)
            }
        }
    }

    /** Downloads and verifies a version newer than ours (and than [skip]), or null. */
    private fun fetch(context: Context, skip: Long): Ready? {
        val versionUrl = URI(BuildConfig.UPDATE_URL)
        val info = JSONObject(String(get(versionUrl) { it.readBytes() }, Charsets.UTF_8))
        val code = info.getLong("versionCode")
        if (code <= BuildConfig.VERSION_CODE) return null
        if (info.optInt("minSdk", 0) > Build.VERSION.SDK_INT) return null
        val versionName = info.optString("versionName")
        // The star on the Settings icon, which stays through "Later".
        available.value = versionName
        if (code == skip) return null
        Log.i(TAG, "update available: $versionName ($code)")

        val apk = File(context.cacheDir, "update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        get(versionUrl.resolve(info.getString("apk"))) { input ->
            apk.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        // Required: a version.json without one is refused, not trusted.
        val expected = info.optString("sha256").lowercase()
        if (expected.isEmpty() || actual != expected) {
            apk.delete()
            throw IOException(if (expected.isEmpty()) "version.json has no sha256" else "APK sha256 doesn't match version.json")
        }
        return Ready(code, versionName, apk, info.optString("notes").takeIf { it.isNotBlank() })
    }

    private fun offer(activity: Activity, update: Ready) {
        if (dialog?.isShowing == true) return
        val name = activity.getString(R.string.app_name)
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_offer_title, name))
            .setMessage(activity.getString(R.string.update_offer_text, update.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_install) { _, _ ->
                ready = null
                val app = activity.applicationContext
                executor.execute {
                    try {
                        install(app, update.apk, update.versionName)
                    } catch (e: Exception) {
                        Log.w(TAG, "install failed: $e")
                    }
                }
            }
            .setNegativeButton(R.string.update_later) { _, _ ->
                ready = null
                declined = update.code
            }
            .apply {
                // Opens over the offer and comes back to it: the offer is still waiting (ready).
                if (update.notes != null) setNeutralButton(R.string.update_whats_new) { _, _ -> showNotes(activity, update) }
            }
            .setCancelable(false) // an outside tap shouldn't count as an answer
            .show()
    }

    private fun showNotes(activity: Activity, update: Ready) {
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_whats_new_title, update.versionName))
            .setMessage(update.notes)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { if (ready === update && !activity.isFinishing) offer(activity, update) }
            .show()
    }

    private fun install(context: Context, apk: File, versionName: String) {
        val installer = context.packageManager.packageInstaller
        // A session left over from an update that was declined or interrupted.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once this app is the installer of record (after its first
            // self-update), the system may skip the confirmation entirely.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val status = Intent(context, UpdateReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_VERSION_NAME, versionName)
            // Mutable: the installer adds the status and confirmation intent as extras.
            val pending = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun <T> get(uri: URI, read: (InputStream) -> T): T {
        val c = uri.toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $uri")
            return c.inputStream.use(read)
        } finally {
            c.disconnect()
        }
    }
}
