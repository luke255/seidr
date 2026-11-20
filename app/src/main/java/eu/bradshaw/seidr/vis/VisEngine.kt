package eu.bradshaw.seidr.vis

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * projectM and everything that decides what it shows: the shuffled run of
 * presets (loved ones about one time in ten), [Autotune]'s quality level
 * and pace, presets too heavy for this device, and the 30 fps last resort. Shared by
 * [VisualiserActivity] (a GL surface on a screen) and
 * [eu.bradshaw.seidr.vis.xr.XrVisualiserActivity] (the headset); each
 * hands it a GL context and a [Host] for what only it can do.
 *
 * Every call on the GL thread, with the context current. projectM draws to
 * the default framebuffer at the size given to [resize].
 */
class VisEngine(
    private val context: Context,
    private val prefs: VisPrefs,
    private val presets: List<String>,
    /** Shows just this preset (its path), whatever it costs. */
    private val preview: String?,
    val tune: Autotune,
    private val host: Host,
) {
    interface Host {
        /** Render at [scale] of full size from now on. On the GL thread. */
        fun applyScale(scale: Float)
        /** [path] is now showing. On the GL thread. */
        fun presetShown(path: String)
        /** This device settled for [fps] for good: pace to it from now on. On the GL thread. */
        fun retargeted(fps: Float)
        /** Pace to [fps] for now ([Autotune.pace]: halved for a heavy preset when quality comes first). On the GL thread. */
        fun pace(fps: Float)
    }

    private var handle = 0L
    private var index = -1
    private var current: String? = null
    /** Presets shown before [current], newest last, for [previous]. */
    private val back = ArrayDeque<String>()
    /** Presets [previous] stepped back over, next first, for [next] to return to. */
    private val ahead = ArrayDeque<String>()
    private val pcm = FloatArray(4096 * 2)
    var width = 0
        private set
    var height = 0
        private set
    private val verdicts = ArrayDeque<Boolean>() // recent presets: too heavy or not
    private var judged = false // the current preset already has a verdict (or failed to load)
    private val sprite = LogoSprite(context)
    private var paced = tune.targetFps // what the host is pacing to
    /** The app's ð over the preset ([LogoSprite]), for the visualiser's logo page. Any thread. */
    @Volatile var logo = false

    /** A fresh GL context: everything projectM held in the last one is gone. */
    fun create(textures: File) {
        if (handle != 0L) ProjectM.nativeDestroy(handle)
        handle = ProjectM.nativeCreate(textures.absolutePath, 1, 1)
        sprite.create()
        configure()
        // Cut, not blended: with nothing showing yet, projectM would blend from its own idle logo.
        next(hardCut = true)
    }

    fun resize(width: Int, height: Int) {
        this.width = width; this.height = height
        if (handle != 0L) ProjectM.nativeResize(handle, width, height)
    }

    /** Renders one frame, fed what's audible, and lets the tuner react. */
    fun frame(now: Long) {
        if (handle == 0L) return
        val n = VisAudio.readAudible(pcm)
        ProjectM.nativeAddPcm(handle, pcm, n)
        val events = ProjectM.nativeRender(handle)
        sprite.draw(logo, now, width, height, pcm, n)
        if (events and ProjectM.LOAD_FAILED != 0) judged = true
        if (events and (ProjectM.LOAD_FAILED or ProjectM.SWITCH_REQUESTED) != 0) next(hardCut = events and ProjectM.HARD_CUT != 0)

        when (tune.onFrame(now)) {
            Autotune.Change.LEVEL -> {
                val l = tune.current
                Log.i(TAG, "%.1f fps -> level %d: scale %.2f, mesh %dx%d, transitions %b".format(
                    tune.basisFps, tune.level, l.scale, l.meshX, l.meshY, l.transitions,
                ))
                configure()
                host.applyScale(l.scale)
            }
            // A preview shows the preset whatever it costs.
            Autotune.Change.TOO_HEAVY -> if (preview == null) current?.let { path ->
                Log.i(TAG, "too heavy at the lowest quality, hiding it on this device: $path")
                prefs.markTooHeavy(path)
                verdict(heavy = true)
                judged = true
                if (!lastResort(now)) next(hardCut = false)
            }
            Autotune.Change.NONE -> {}
        }
        if (tune.pace != paced) {
            paced = tune.pace
            Log.i(TAG, "pacing to $paced fps")
            host.pace(paced)
        }
    }

    /** The tuner's readout: frame rate, render size, mesh, transitions, level, audio lead. */
    fun stats(): String {
        val l = tune.current
        return "%.0f/%.0f fps · %d×%d · mesh %d×%d · blend %s · level %d · audio −%d ms".format(
            tune.fps, tune.pace, width, height, l.meshX, l.meshY, if (l.transitions) "on" else "off", tune.level, VisAudio.aheadMs().coerceAtLeast(0), // -1: no audio yet
        )
    }

    private fun configure() {
        val l = tune.current
        ProjectM.nativeConfigure(
            handle,
            // A preview holds its one preset.
            presetSeconds = if (preview != null) 1e6 else PRESET_SECONDS,
            blendSeconds = if (l.transitions) 3.0 else 0.0,
            meshW = l.meshX, meshH = l.meshY,
            hardCuts = preview == null,
        )
    }

    /** Tallies the last [VERDICTS] presets: too heavy, or ran. */
    private fun verdict(heavy: Boolean) {
        verdicts.addLast(heavy)
        while (verdicts.size > VERDICTS) verdicts.removeFirst()
    }

    /**
     * Most recent presets too heavy for the target even at the lowest
     * quality: this device can't do 60, so it settles for 30, for good,
     * and judges presets afresh (see [VisPrefs.fallBackTo]).
     */
    private fun lastResort(now: Long): Boolean {
        // Quality first judged them at half the rate already: dropping to it wouldn't save them.
        if (tune.qualityFirst || tune.targetFps <= LAST_RESORT_FPS || verdicts.size < VERDICTS || verdicts.count { it } < VERDICTS_HEAVY) return false
        Log.i(TAG, "most presets are too heavy at ${tune.targetFps} fps: dropping to $LAST_RESORT_FPS for good")
        prefs.fallBackTo(LAST_RESORT_FPS)
        verdicts.clear()
        tune.retarget(LAST_RESORT_FPS, now)
        host.retargeted(LAST_RESORT_FPS)
        configure()
        host.applyScale(tune.current.scale)
        next(hardCut = false)
        return true
    }

    /** Hides the playing preset for good (Settings → Visualiser undoes it) and moves on. */
    fun hideCurrent() {
        val path = current ?: return
        prefs.ban(path)
        judged = true
        current = null // not one to come back to
        next(hardCut = true)
    }

    /**
     * The next preset: blended in if this device has room for transitions, cut
     * to on a hard cut. After [previous], the ones it stepped back over first.
     */
    fun next(hardCut: Boolean) {
        if (handle == 0L || presets.isEmpty()) return
        val hidden = prefs.hiddenPaths
        while (ahead.firstOrNull()?.let { it in hidden } == true) ahead.removeFirst()
        val path = ahead.removeFirstOrNull() ?: pick() ?: return
        current?.let { back.addLast(it); while (back.size > HISTORY) back.removeFirst() }
        load(path, hardCut)
    }

    /** Back to the preset shown before this one, cut to; false if there's none. */
    fun previous(): Boolean {
        if (handle == 0L || preview != null) return false
        val hidden = prefs.hiddenPaths
        while (back.lastOrNull()?.let { it in hidden } == true) back.removeLast()
        val path = back.removeLastOrNull() ?: return false
        current?.let { ahead.addFirst(it) }
        load(path, hardCut = true)
        return true
    }

    private fun load(path: String, hardCut: Boolean) {
        // A preset that ran its course without being judged too heavy counts as one that ran.
        if (current != null && !judged) verdict(heavy = false)
        judged = false
        remember()
        current = path
        host.presetShown(path)
        val data = eu.bradshaw.seidr.Graph.get(context).presetPack.read(path)
        val start = SystemClock.elapsedRealtime()
        ProjectM.nativeLoadPreset(handle, data, tune.current.transitions && !hardCut)
        Log.i(TAG, "preset $path (load ${SystemClock.elapsedRealtime() - start} ms${if (hardCut) ", hard cut" else ""})")
        if (tune.presetChanged(SystemClock.elapsedRealtimeNanos(), prefs.levelOf(path, tune.qualityFirst))) {
            configure()
            host.applyScale(tune.current.scale)
        }
    }

    /**
     * The next in the shuffled run, skipping any hidden since it was
     * shuffled. About one time in ten, a loved preset instead (not the
     * one just shown), so favourites come round often.
     */
    private fun pick(): String? {
        if (preview != null) return presets.first()
        val loved = (prefs.lovedPaths - setOfNotNull(current)).toList()
        if (loved.isNotEmpty() && Math.random() < LOVED_SHARE) return loved.random()
        val hidden = prefs.hiddenPaths
        repeat(presets.size) {
            index = (index + 1) % presets.size
            if (presets[index] !in hidden) return presets[index]
        }
        return null
    }

    /** Where the current preset settled, and (outside previews) where this device usually runs, for next time. */
    fun remember() {
        current?.let { prefs.setLevelOf(it, tune.qualityFirst, tune.level) }
        if (preview == null) prefs.setTypicalLevel(tune.qualityFirst, tune.typicalLevel())
    }

    fun release() {
        if (handle != 0L) sprite.release()
        if (handle != 0L) ProjectM.nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        const val TAG = "Milkdrop"
        private const val PRESET_SECONDS = 30.0
        private const val LAST_RESORT_FPS = 30f
        private const val VERDICTS = 10
        private const val VERDICTS_HEAVY = 6
        /** How many presets back [previous] can go. */
        private const val HISTORY = 50
        // MilkDrop weighted its random pick by star rating (5 stars ≈ 1.7× a 3),
        // which across ~9,800 presets would all but never bring a favourite back.
        private const val LOVED_SHARE = 0.1

        /** [preview] alone, or the pack (less what's hidden) shuffled. */
        fun presets(context: Context, prefs: VisPrefs, preview: String?): List<String> {
            if (preview != null) return listOf(preview)
            val hidden = prefs.hiddenPaths
            return eu.bradshaw.seidr.Graph.get(context).presetPack.presets.filter { it !in hidden }.shuffled()
        }

        /** The texture pack, from the downloaded [PresetPack]: projectM wants files on disk. */
        fun textureDir(context: Context): File = eu.bradshaw.seidr.Graph.get(context).presetPack.textureDir
    }
}
