package eu.bradshaw.seidr.vis

import android.content.Context
import androidx.core.content.edit
import eu.bradshaw.seidr.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The visualiser's settings and per-device memory, shared by
 * [VisualiserActivity] and Settings → Visualiser (SharedPreferences
 * "visualiser"; device-local, never synced).
 *
 * Presets are hidden two ways: by the user (the flag in the visualiser's
 * controls) or by the tuner, when one can't hold even 45 fps at the lowest
 * quality on this device. Either can be unhidden in Settings. Loved presets
 * (the heart) come up about one time in ten.
 */
class VisPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("visualiser", Context.MODE_PRIVATE)

    /** A preset in the pack, by path; [byUser] is false for one the tuner hid. */
    data class Preset(val path: String, val byUser: Boolean = true) {
        /** The preset's name: its file name without ".milk". */
        val name get() = path.substringAfterLast('/').removeSuffix(".milk")
        /** Its folders in the pack, e.g. "Sparkle / Explosions". */
        val folder get() = path.substringBeforeLast('/', "").replace("/", " / ")
    }

    private val _showInfo = MutableStateFlow(prefs.getBoolean(SHOW_INFO, true))
    /** Cover, title and time in the corner all the time (else only with the controls). */
    val showInfo: StateFlow<Boolean> = _showInfo.asStateFlow()

    fun setShowInfo(value: Boolean) {
        prefs.edit { putBoolean(SHOW_INFO, value) }
        _showInfo.value = value
    }

    private val _showStats = MutableStateFlow(prefs.getBoolean(SHOW_STATS, BuildConfig.DEBUG))
    /** The tuner's readout (frame rate, render size, level…) in a corner. On by default in debug builds. */
    val showStats: StateFlow<Boolean> = _showStats.asStateFlow()

    fun setShowStats(value: Boolean) {
        prefs.edit { putBoolean(SHOW_STATS, value) }
        _showStats.value = value
    }

    private val _hidden = MutableStateFlow(loadHidden())
    /** Hidden presets, by name. */
    val hidden: StateFlow<List<Preset>> = _hidden.asStateFlow()

    private val _loved = MutableStateFlow(loadLoved())
    /** Loved presets, by name. */
    val loved: StateFlow<List<Preset>> = _loved.asStateFlow()

    val lovedPaths: Set<String> get() = prefs.getStringSet(LOVED, emptySet()).orEmpty()

    fun setLoved(path: String, loved: Boolean) =
        update { putStringSet(LOVED, if (loved) lovedPaths + path else lovedPaths - path) }

    val hiddenPaths: Set<String> get() = banned + tooHeavy

    private val banned get() = prefs.getStringSet(BANNED, emptySet()).orEmpty()
    private val tooHeavy get() = prefs.getStringSet(TOO_HEAVY, emptySet()).orEmpty()

    /** The user hid [path] (it's no longer loved, either). */
    fun ban(path: String) = update {
        putStringSet(BANNED, banned + path)
        putStringSet(LOVED, lovedPaths - path)
    }

    /** [path] can't hold the frame rate on this device even at the lowest quality. */
    fun markTooHeavy(path: String) = update { putStringSet(TOO_HEAVY, tooHeavy + path) }

    fun unhide(path: String) = update {
        putStringSet(BANNED, banned - path)
        putStringSet(TOO_HEAVY, tooHeavy - path)
        remove(LEVEL_OF + path) // judge it afresh
    }

    // --- The tuner's memory (see Autotune) -------------------------------------

    /** The frame rate to hold to: 60, or 30 once this device has fallen back. */
    val targetFps: Float get() = prefs.getFloat(TARGET, 60f)

    /** Where this device usually runs. */
    var typicalLevel: Int
        get() = prefs.getInt(LEVEL, 0)
        set(value) = prefs.edit { putInt(LEVEL, value) }

    fun levelOf(path: String): Int? = prefs.getInt(LEVEL_OF + path, -1).takeIf { it >= 0 }

    fun setLevelOf(path: String, level: Int) = prefs.edit { putInt(LEVEL_OF + path, level) }

    /**
     * Settles for [fps] for good. What the tuner learned at the old rate goes
     * (levels, presets too heavy); what the user hid stays.
     */
    fun fallBackTo(fps: Float) = update {
        putFloat(TARGET, fps)
        remove(TOO_HEAVY)
        remove(LEVEL)
        prefs.all.keys.filter { it.startsWith(LEVEL_OF) }.forEach { remove(it) }
    }

    /**
     * Once: frame pacing used to round each frame up to whole vsyncs, so where
     * the panel's rate isn't a multiple of 60 (the Quest's 72 Hz) every preset
     * looked too heavy, and the tuner learned a 30 fps fallback, a low level and
     * presets to hide. Forgets all that there (returning true); elsewhere it
     * was learned fairly.
     */
    fun forgetPacingBug(refreshHz: Float): Boolean {
        if (prefs.getBoolean(PACING_FIXED, false)) return false
        val ratio = refreshHz / 60f
        val skewed = abs(ratio - ratio.roundToInt()) > 0.02f
        update {
            putBoolean(PACING_FIXED, true)
            if (skewed) {
                remove(TARGET)
                remove(TOO_HEAVY)
                remove(LEVEL)
                prefs.all.keys.filter { it.startsWith(LEVEL_OF) }.forEach { remove(it) }
            }
        }
        return skewed
    }

    private fun update(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block) // apply(): memory now, disk later
        _hidden.value = loadHidden()
        _loved.value = loadLoved()
    }

    private fun loadHidden(): List<Preset> =
        (banned.map { Preset(it, byUser = true) } + (tooHeavy - banned).map { Preset(it, byUser = false) })
            .sortedBy { it.name.lowercase() }

    private fun loadLoved(): List<Preset> = lovedPaths.map { Preset(it) }.sortedBy { it.name.lowercase() }

    private companion object {
        const val SHOW_INFO = "show_info"
        const val SHOW_STATS = "show_stats"
        const val BANNED = "banned"
        const val LOVED = "loved"
        const val TOO_HEAVY = "too_heavy"
        const val TARGET = "target_fps"
        const val LEVEL = "level"
        const val LEVEL_OF = "level:" // + preset path: where it settled last time
        const val PACING_FIXED = "pacing_fixed" // see forgetPacingBug
    }
}
