package eu.bradshaw.seidr.vis

/**
 * Holds the visualiser at the target frame rate by trading quality live.
 *
 * Quality is a ladder ([LEVELS]): first a finer per-pixel mesh than usual,
 * for devices with room to spare; then preset transitions (a transition
 * renders two presets at once); then render resolution (the hardware scaler
 * upscales for free), and the mesh again.
 *
 * Every half second it looks at the frame rate it delivered. Short of the
 * target, it drops at once to the level that frame rate predicts (cost taken
 * to follow pixel count; if that's optimistic, the next window drops again).
 * Steady at the target, it steps up one level to look for headroom. A step
 * up that costs frames is taken back and becomes this preset's ceiling, so
 * each preset stutters at most once per level. A drop that buys next to no
 * frames means resolution isn't the cost (a preset's equations, on the CPU,
 * can be): the quality comes back and stays, at whatever rate that gives. A
 * new preset starts where recent ones settled ([typicalLevel]).
 *
 * With [qualityFirst] (Settings → Visualiser → Favour quality), the first
 * step down is to half the frame rate at full quality, and only then the
 * ladder, at that rate (see [ladderFor]). (Timing frames with glFinish
 * was tried and misled it: that runs CPU and GPU back to back, which a real
 * frame doesn't.)
 *
 * Pure: the caller feeds it frame times and applies [level] when [onFrame]
 * says it changed. All calls on the GL thread.
 */
class Autotune(private var start: Int, targetFps: Float, val qualityFirst: Boolean = false) {
    data class Level(val scale: Float, val meshX: Int, val transitions: Boolean) {
        val meshY get() = meshX * 3 / 4
    }

    /** A step on the ladder: a quality [level] held at [fps]. */
    data class Rung(val level: Level, val fps: Float)

    enum class Change { NONE, LEVEL, TOO_HEAVY }

    /** The frame rate it holds to at best: the display's, up to 60; 30 as a last resort ([retarget]). */
    var targetFps = targetFps
        private set

    private var ladder = ladderFor(targetFps, qualityFirst)

    /** The rung it's on: an index into [ladder] (the same as [LEVELS] unless [qualityFirst]). */
    var level = start.coerceIn(0, ladder.lastIndex)
        private set

    val current get() = ladder[level].level

    /** The frame rate to pace to now: [targetFps], or half of it on [qualityFirst]'s lower rungs. */
    val pace get() = ladder[level].fps

    /** Frame rate, smoothed over about a quarter of a second, for display. */
    var fps = 0f
        private set

    /** The frame rate the last change was based on, for logging. */
    var basisFps = 0f
        private set

    private var lastFrame = 0L
    private var windowStart = 0L
    private var frames = 0
    private var settleUntil = 0L
    private var goodSince = 0L
    private var ceiling = 0 // best level this preset may probe to
    private var probingFrom = -1 // level we probed up from, while a probe is on trial
    private var probeStart = 0L
    private var bottomSince = 0L
    private var mildlyShort = 0 // windows in a row a little short: one alone is a hitch, not a trend
    private var presetStart = 0L
    private var heavy = false // this preset was called too heavy
    private var dropFrom = -1 // the level the last drop left, while it's unproven
    private var dropFps = 0f // the frame rate there
    private var stuck = false // dropping doesn't help this preset: it stays put
    private val settled = ArrayDeque<Int>() // where recent presets ended up, newest last

    /**
     * A new preset: its load stalls a frame or two. Starts it at [known] if
     * seen before, else where this device usually runs (not wherever the last,
     * maybe heavy, preset left off).
     */
    fun presetChanged(now: Long, known: Int?): Boolean {
        outcome(now)?.let {
            settled.addLast(it)
            while (settled.size > SETTLED) settled.removeFirst()
        }
        val next = (known ?: typicalLevel()).coerceIn(0, ladder.lastIndex)
        val changed = next != level
        level = next
        presetStart = now
        heavy = false
        dropFrom = -1
        stuck = false
        probingFrom = -1
        ceiling = 0
        bottomSince = 0L
        settle(now)
        return changed
    }

    /** A new frame rate to hold to (the 30 fps last resort): judge afresh from the top. */
    fun retarget(fps: Float, now: Long) {
        targetFps = fps
        ladder = ladderFor(fps, qualityFirst)
        start = 0
        level = 0
        dropFrom = -1
        stuck = false
        probingFrom = -1
        ceiling = 0
        bottomSince = 0L
        settled.clear()
        settle(now)
    }

    /** One rendered frame, at [now]. */
    fun onFrame(now: Long): Change {
        if (lastFrame != 0L) {
            val dt = (now - lastFrame).coerceAtMost(500_000_000L)
            val instant = 1e9f / dt.coerceAtLeast(1)
            fps = if (fps == 0f) instant else fps + (instant - fps) * (dt / 250e6f).coerceAtMost(1f)
        }
        // The first moments cost extra (shaders compiling, the overlay's first layout): don't judge them.
        if (lastFrame == 0L) settleUntil = maxOf(settleUntil, now + STARTUP_NS)
        lastFrame = now
        if (now < settleUntil) return Change.NONE
        if (windowStart == 0L) { windowStart = now; frames = 0; return Change.NONE }
        frames++
        val elapsed = now - windowStart
        if (elapsed < WINDOW_NS) return Change.NONE

        val windowFps = (frames * 1e9 / elapsed).toFloat()
        windowStart = now; frames = 0
        val target = pace
        val short = windowFps < target * 0.94f // ~56 of 60: a missed vsync now and then is fine

        if (probingFrom >= 0) {
            if (short) {
                // That step up cost frames: back down, and it's this preset's limit.
                ceiling = probingFrom
                level = probingFrom; probingFrom = -1
                return changed(now, windowFps)
            }
            if (now - probeStart >= PROBE_TRIAL_NS) probingFrom = -1
            return Change.NONE
        }

        if (!short) { mildlyShort = 0; dropFrom = -1 }
        if (short) {
            goodSince = now
            // A preset load or a GC can cost one window a few frames: only act on a
            // mild shortfall that lasts. A big one is real, so act on it at once.
            if (windowFps >= target * 0.85f && ++mildlyShort < 2) return Change.NONE
            if (dropFrom >= 0 && windowFps < dropFps * 1.08f) {
                // The last drop bought next to nothing: take the quality back and keep it.
                level = dropFrom; dropFrom = -1; stuck = true
                return changed(now, windowFps)
            }
            if (stuck || level == ladder.lastIndex) {
                // Nothing left to give. Give it a few seconds before calling the preset too heavy.
                if (bottomSince == 0L) bottomSince = now
                if (windowFps < target * 0.75f && now - bottomSince >= HEAVY_NS) { heavy = true; return Change.TOO_HEAVY }
                return Change.NONE
            }
            if (ladder[level + 1].fps < target) {
                // Quality first: halve the frame rate, quality untouched, before anything else.
                // (Halving it always shows fewer frames, so it isn't judged like a drop in quality.)
                level++; dropFrom = -1
                return changed(now, windowFps)
            }
            dropFrom = level; dropFps = windowFps
            level = fit(windowFps).coerceAtLeast(level + 1)
            return changed(now, windowFps)
        }

        bottomSince = 0L
        if (level > ceiling && now - goodSince >= PROBE_WAIT_NS) {
            probingFrom = level; probeStart = now
            level--
            return changed(now, windowFps)
        }
        return Change.NONE
    }

    /** The best rung predicted to hold its frame rate, from [fps] on the current one, if cost follows pixel count. */
    private fun fit(fps: Float): Int {
        val cost = 1.0 / fps // relative units: the budget is 1 / the rung's frame rate
        val s = current.scale.toDouble()
        for (i in ladder.indices) {
            val r = ladder[i].level.scale / s
            // A transition renders two presets at once: only keep them with room for both.
            val share = if (ladder[i].level.transitions) 0.5 else 1.0
            if (cost * r * r <= share * 0.9 / ladder[i].fps) return i
        }
        return ladder.lastIndex
    }

    /**
     * Where this device usually runs, to start a new preset at: the median of
     * where recent presets (this one too, once it's had a while) ended up.
     * Presets called too heavy don't count, nor (by the median) the odd
     * expensive one, so neither drags the next to the bottom. Before any
     * have, the level this session began at.
     */
    fun typicalLevel(): Int {
        val all = settled + listOfNotNull(outcome(lastFrame))
        return if (all.isEmpty()) start else all.sorted()[all.size / 2]
    }

    /** Where this preset ended up, if it ran long enough to say and wasn't too heavy. */
    private fun outcome(now: Long): Int? =
        if (heavy || now - presetStart < OUTCOME_NS) null else if (probingFrom >= 0) probingFrom else level

    private fun changed(now: Long, windowFps: Float): Change {
        basisFps = windowFps
        settle(now)
        return Change.LEVEL
    }

    private fun settle(now: Long) {
        mildlyShort = 0
        settleUntil = now + SETTLE_NS
        windowStart = 0L
        goodSince = now
    }

    companion object {
        val LEVELS = listOf(
            // Richer than projectM's default for devices with room: a finer warp
            // mesh makes the flowing distortions smoother.
            Level(1f, 64, true),
            Level(1f, 48, true),
            Level(1f, 32, true),
            Level(1f, 32, false),
            Level(0.85f, 32, false),
            Level(0.72f, 32, false),
            Level(0.6f, 32, false),
            Level(0.5f, 32, false),
            Level(0.42f, 24, false),
            Level(0.35f, 24, false),
            Level(0.3f, 16, false),
            Level(0.25f, 16, false),
        )

        private const val WINDOW_NS = 500_000_000L
        private const val SETTLE_NS = 400_000_000L
        private const val STARTUP_NS = 1_500_000_000L
        private const val PROBE_WAIT_NS = 1_500_000_000L
        private const val PROBE_TRIAL_NS = 1_500_000_000L
        private const val OUTCOME_NS = 10_000_000_000L // long enough to have found its level
        private const val SETTLED = 9
        private const val HEAVY_NS = 4_000_000_000L

        /**
         * The rungs, best first. Normally [LEVELS] at [target]. Quality first:
         * the best level at [target], then every level at half of it (60 →
         * 30 divides the panel's vsyncs evenly); with [target] already 30 or
         * less, there's nothing to halve.
         */
        fun ladderFor(target: Float, qualityFirst: Boolean): List<Rung> =
            if (!qualityFirst || target <= HALVES_FROM) LEVELS.map { Rung(it, target) }
            else listOf(Rung(LEVELS.first(), target)) + LEVELS.map { Rung(it, target / 2) }

        private const val HALVES_FROM = 31f
    }
}
