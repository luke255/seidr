package eu.bradshaw.seidr.vis

import android.content.Context
import eu.bradshaw.seidr.data.nameOrder
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.bradshaw.seidr.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The visualiser's settings and per-device memory, shared by
 * [VisualiserActivity] and Settings → Visualiser. Device-local, never synced.
 *
 * What's about the person (what shows over it, loved and hidden presets) is
 * kept per user ([user]: the signed-in one, or whoever is playing here over
 * Connect), so it comes back when they next use this device. What's about the
 * device (the tuner's memory, presets too heavy for it) is shared by everyone.
 *
 * Presets are hidden two ways: by the user (the flag in the visualiser's
 * controls) or by the tuner, when one can't hold even 45 fps at the lowest
 * quality on this device. Either can be unhidden in Settings. Loved presets
 * (the heart) come up about one time in ten.
 */
class VisPrefs(private val context: Context, user: StateFlow<String?>, scope: CoroutineScope) {
    /** The device's: the tuner's memory. */
    private val prefs = context.getSharedPreferences("visualiser", Context.MODE_PRIVATE)
    /** The current user's: display toggles, loved and hidden presets. */
    @Volatile private var mine = userPrefs(user.value)

    private fun userPrefs(user: String?): SharedPreferences {
        val name = user?.let { u -> "visualiser_user_" + u.toByteArray().joinToString("") { "%02x".format(it) }.take(64) } ?: "visualiser_nobody"
        val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        // Once: these were the device's before they were per user; the first user to sign in keeps them.
        if (user != null && prefs.contains(MIGRATED).not()) {
            p.edit {
                if (!p.contains(LOVED)) prefs.getStringSet(LOVED, null)?.let { putStringSet(LOVED, it) }
                if (!p.contains(BANNED)) prefs.getStringSet(BANNED, null)?.let { putStringSet(BANNED, it) }
                if (!p.contains(SHOW_INFO) && prefs.contains(SHOW_INFO)) putBoolean(SHOW_INFO, prefs.getBoolean(SHOW_INFO, true))
                if (!p.contains(SHOW_STATS) && prefs.contains(SHOW_STATS)) putBoolean(SHOW_STATS, prefs.getBoolean(SHOW_STATS, false))
            }
            prefs.edit { remove(LOVED); remove(BANNED); remove(SHOW_INFO); remove(SHOW_STATS); putBoolean(MIGRATED, true) }
        }
        return p
    }

    /** A preset in the pack, by path; [byUser] is false for one the tuner hid. */
    data class Preset(val path: String, val byUser: Boolean = true) {
        /** The preset's name: its file name without ".milk". */
        val name get() = path.substringAfterLast('/').removeSuffix(".milk")
        /** Its folders in the pack, e.g. "Sparkle / Explosions". */
        val folder get() = path.substringBeforeLast('/', "").replace("/", " / ")
    }

    private val _showInfo = MutableStateFlow(mine.getBoolean(SHOW_INFO, true))
    /** Cover, title and time in the corner all the time (else only with the controls). */
    val showInfo: StateFlow<Boolean> = _showInfo.asStateFlow()

    fun setShowInfo(value: Boolean) {
        mine.edit { putBoolean(SHOW_INFO, value) }
        _showInfo.value = value
    }

    private val _showStats = MutableStateFlow(mine.getBoolean(SHOW_STATS, BuildConfig.DEBUG))
    /** The tuner's readout (frame rate, render size, level…) in a corner. On by default in debug builds. */
    val showStats: StateFlow<Boolean> = _showStats.asStateFlow()

    fun setShowStats(value: Boolean) {
        mine.edit { putBoolean(SHOW_STATS, value) }
        _showStats.value = value
    }

    private val _hidden = MutableStateFlow(loadHidden())
    /** Hidden presets, by name. */
    val hidden: StateFlow<List<Preset>> = _hidden.asStateFlow()

    private val _loved = MutableStateFlow(loadLoved())
    /** Loved presets, by name. */
    val loved: StateFlow<List<Preset>> = _loved.asStateFlow()

    val lovedPaths: Set<String> get() = mine.getStringSet(LOVED, emptySet()).orEmpty()

    fun setLoved(path: String, loved: Boolean) =
        updateMine { putStringSet(LOVED, if (loved) lovedPaths + path else lovedPaths - path) }

    val hiddenPaths: Set<String> get() = banned + tooHeavy

    private val banned get() = mine.getStringSet(BANNED, emptySet()).orEmpty()
    private val tooHeavy get() = prefs.getStringSet(TOO_HEAVY, emptySet()).orEmpty()

    /** The user hid [path] (it's no longer loved, either). */
    fun ban(path: String) = updateMine {
        putStringSet(BANNED, banned + path)
        putStringSet(LOVED, lovedPaths - path)
    }

    /** [path] can't hold the frame rate on this device even at the lowest quality. */
    fun markTooHeavy(path: String) = update { putStringSet(TOO_HEAVY, tooHeavy + path) }

    fun unhide(path: String) {
        updateMine { putStringSet(BANNED, banned - path) }
        unhideHeavy(path)
    }

    private fun unhideHeavy(path: String) = update {
        putStringSet(TOO_HEAVY, tooHeavy - path)
        remove(LEVEL_OF + path) // judge it afresh
        remove(Q_LEVEL_OF + path)
    }

    // --- The flashing-lights warning (see VisWarning) ---------------------------

    /** This device has shown the warning for the flat ([immersive] false) or the headset's visualiser. */
    fun warned(immersive: Boolean): Boolean = prefs.getBoolean(if (immersive) WARNED_XR else WARNED, false)

    fun setWarned(immersive: Boolean) = prefs.edit { putBoolean(if (immersive) WARNED_XR else WARNED, true) }

    // --- The tuner's memory (see Autotune) -------------------------------------

    private val _qualityFirst = MutableStateFlow(prefs.getBoolean(QUALITY_FIRST, false))
    /**
     * Favour quality: a preset that can't hold the frame rate halves it before
     * losing any quality ([Autotune.qualityFirst]). This device's, as the
     * trade depends on its GPU; read when the visualiser opens.
     */
    val qualityFirst: StateFlow<Boolean> = _qualityFirst.asStateFlow()

    fun setQualityFirst(value: Boolean) {
        prefs.edit { putBoolean(QUALITY_FIRST, value) }
        _qualityFirst.value = value
    }

    /** The frame rate to hold to: 60, or 30 once this device has fallen back. */
    val targetFps: Float get() = prefs.getFloat(TARGET, 60f)

    // Levels are rungs on the tuner's ladder, which differs with qualityFirst, so each way keeps its own.

    /** Where this device usually runs. */
    fun typicalLevel(qualityFirst: Boolean): Int = prefs.getInt(if (qualityFirst) Q_LEVEL else LEVEL, 0)

    fun setTypicalLevel(qualityFirst: Boolean, level: Int) = prefs.edit { putInt(if (qualityFirst) Q_LEVEL else LEVEL, level) }

    fun levelOf(path: String, qualityFirst: Boolean): Int? =
        prefs.getInt((if (qualityFirst) Q_LEVEL_OF else LEVEL_OF) + path, -1).takeIf { it >= 0 }

    fun setLevelOf(path: String, qualityFirst: Boolean, level: Int) =
        prefs.edit { putInt((if (qualityFirst) Q_LEVEL_OF else LEVEL_OF) + path, level) }

    /** Forgets every level learned, both ways. */
    private fun SharedPreferences.Editor.forgetLevels() {
        remove(LEVEL)
        remove(Q_LEVEL)
        prefs.all.keys.filter { it.startsWith(LEVEL_OF) || it.startsWith(Q_LEVEL_OF) }.forEach { remove(it) }
    }

    /**
     * Settles for [fps] for good. What the tuner learned at the old rate goes
     * (levels, presets too heavy); what the user hid stays.
     */
    fun fallBackTo(fps: Float) = update {
        putFloat(TARGET, fps)
        remove(TOO_HEAVY)
        forgetLevels()
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
                forgetLevels()
            }
        }
        return skewed
    }

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block) // apply(): memory now, disk later
        _hidden.value = loadHidden()
        _loved.value = loadLoved()
    }

    private fun updateMine(block: SharedPreferences.Editor.() -> Unit) {
        mine.edit(action = block)
        _hidden.value = loadHidden()
        _loved.value = loadLoved()
    }

    private fun loadHidden(): List<Preset> =
        (banned.map { Preset(it, byUser = true) } + (tooHeavy - banned).map { Preset(it, byUser = false) })
            .sortedWith(nameOrder { it.name })

    private fun loadLoved(): List<Preset> = lovedPaths.map { Preset(it) }.sortedWith(nameOrder { it.name })

    // Last, so everything it touches exists.
    init {
        scope.launch {
            user.drop(1).collect { u ->
                mine = userPrefs(u)
                _showInfo.value = mine.getBoolean(SHOW_INFO, true)
                _showStats.value = mine.getBoolean(SHOW_STATS, BuildConfig.DEBUG)
                _hidden.value = loadHidden()
                _loved.value = loadLoved()
            }
        }
    }

    private companion object {
        const val SHOW_INFO = "show_info"
        const val SHOW_STATS = "show_stats"
        const val BANNED = "banned"
        const val LOVED = "loved"
        const val TOO_HEAVY = "too_heavy"
        const val TARGET = "target_fps"
        const val LEVEL = "level"
        const val LEVEL_OF = "level:" // + preset path: where it settled last time
        const val QUALITY_FIRST = "quality_first"
        const val Q_LEVEL = "q_level" // the same two, on the quality-first ladder
        const val Q_LEVEL_OF = "q_level:"
        const val PACING_FIXED = "pacing_fixed" // see forgetPacingBug
        const val MIGRATED = "per_user" // the user's settings have moved to their own file
        const val WARNED = "warned"
        const val WARNED_XR = "warned_xr"
    }
}
