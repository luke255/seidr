package eu.bradshaw.seidr.vis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutotuneTest {
    private val frame60 = 16_666_667L

    /**
     * Runs [seconds] of frames on a device whose GPU cost depends on the
     * level, plus [cpuMs] of CPU work that doesn't. CPU and GPU overlap from
     * frame to frame.
     */
    private fun run(
        tune: Autotune, seconds: Double, cpuMs: Double = 0.0, costMs: (Autotune.Level) -> Double,
    ): List<Pair<Long, Autotune.Change>> {
        var now = 1L
        val cpu = (cpuMs * 1e6).toLong()
        val changes = mutableListOf<Pair<Long, Autotune.Change>>()
        while (now < seconds * 1e9) {
            val gpu = (costMs(tune.current) * 1e6).toLong()
            now += maxOf((1e9 / tune.pace).toLong(), cpu, gpu) // paced, as the activity does
            val c = tune.onFrame(now)
            if (c != Autotune.Change.NONE) changes += now to c
        }
        return changes
    }

    // A GPU-bound device: 60 ms a frame at full size, scaling with pixel count, plus fixed CPU work.
    private val slowGpu = { l: Autotune.Level -> 60.0 * l.scale * l.scale + 3 }

    @Test fun `settles on the best level that holds 60`() {
        val tune = Autotune(0, 60f)
        run(tune, 60.0, costMs = slowGpu)
        // 0.42 costs ~13.6 ms; 0.5 costs 18 ms and can't hold 60.
        assertEquals(0.42f, tune.current.scale)
        assertEquals(Autotune.LEVELS.indexOfFirst { it.scale == 0.42f }, tune.typicalLevel())
    }

    @Test fun `gets there within a couple of seconds`() {
        val tune = Autotune(0, 60f)
        run(tune, 2.5, costMs = slowGpu)
        assertTrue("at ${tune.current.scale}", tune.current.scale <= 0.42f)
    }

    @Test fun `stays at full quality when the device keeps up`() {
        val tune = Autotune(0, 60f)
        val changes = run(tune, 30.0) { 6.0 }
        assertEquals(0, tune.level)
        assertTrue(changes.isEmpty())
    }

    @Test fun `drops transitions when there is no room for two presets`() {
        val tune = Autotune(0, 60f)
        run(tune, 30.0) { l -> (if (l.transitions) 18.0 else 12.0) * l.scale * l.scale }
        val best = Autotune.LEVELS.indexOfFirst { !it.transitions }
        assertEquals(best, tune.level)
    }

    @Test fun `climbs back up after a heavy preset`() {
        val tune = Autotune(Autotune.LEVELS.lastIndex, 60f)
        run(tune, 60.0) { l -> 6.0 * l.scale * l.scale + 1 }
        assertEquals(0, tune.level)
    }

    @Test fun `climbs when the cost is mostly CPU work that resolution doesn't touch`() {
        // Like the TV on a busy preset: 12 ms of equations, a GPU that takes 30 ms at 1080p.
        val tune = Autotune(Autotune.LEVELS.lastIndex, 60f)
        run(tune, 40.0, cpuMs = 12.0) { l -> 30.0 * l.scale * l.scale }
        // 0.72 is the sharpest that fits: its GPU takes 15.6 ms, overlapping the CPU's 12.
        assertEquals(0.72f, tune.current.scale)
    }

    @Test fun `one hitch is not a reason to drop`() {
        val tune = Autotune(0, 60f)
        var now = 1L
        var changes = 0
        repeat(600) { i ->
            now += if (i == 300) 70_000_000L else frame60 // a preset load, say
            if (tune.onFrame(now) != Autotune.Change.NONE) changes++
        }
        assertEquals(0, changes)
        assertEquals(0, tune.level)
    }

    @Test fun `a preset seen before starts at its level`() {
        val tune = Autotune(0, 60f)
        assertTrue(tune.presetChanged(1L, known = 5))
        assertEquals(5, tune.level)
        // An unknown one, with nothing settled yet: where the session began.
        assertTrue(tune.presetChanged(2L, known = null))
        assertEquals(0, tune.level)
    }

    @Test fun `a heavy preset doesn't drag the next one down`() {
        val tune = Autotune(0, 60f)
        val changes = run(tune, 12.0) { 40.0 }
        assertTrue(changes.any { it.second == Autotune.Change.TOO_HEAVY })
        tune.presetChanged(13_000_000_000L, known = null)
        assertEquals(0, tune.level)
    }

    @Test fun `keeps its resolution when the CPU is what's short`() {
        // 20 ms of equations a frame: 50 fps whatever the resolution.
        val tune = Autotune(3, 60f)
        val changes = run(tune, 20.0, cpuMs = 20.0) { 4.0 }
        assertEquals(3, tune.level)
        assertTrue(changes.none { it.second == Autotune.Change.TOO_HEAVY })
    }

    @Test fun `a new preset starts where recent ones settled`() {
        val tune = Autotune(0, 60f)
        run(tune, 20.0, costMs = slowGpu)
        val at = Autotune.LEVELS.indexOfFirst { it.scale == 0.42f }
        assertEquals(at, tune.level)
        tune.presetChanged(21_000_000_000L, known = null)
        assertEquals(at, tune.level)
    }

    @Test fun `a failed step up is not retried for the same preset`() {
        val at = Autotune.LEVELS.indexOfFirst { it.scale == 0.42f }
        val tune = Autotune(at, 60f)
        // 0.42 holds 60; 0.5 just misses.
        val changes = run(tune, 60.0, costMs = slowGpu)
        assertEquals(at, tune.level)
        assertEquals(2, changes.size) // one probe up, one back down
    }

    @Test fun `flags a preset nothing can save, but not straight away`() {
        val tune = Autotune(Autotune.LEVELS.lastIndex, 60f)
        val changes = run(tune, 10.0) { 40.0 }
        val heavy = changes.firstOrNull { it.second == Autotune.Change.TOO_HEAVY }
        assertTrue(heavy != null && heavy.first > 4_000_000_000L)
    }

    // --- Quality first --------------------------------------------------------

    @Test fun `quality first stays at 60 when the device keeps up`() {
        val tune = Autotune(0, 60f, qualityFirst = true)
        val changes = run(tune, 30.0) { 6.0 }
        assertEquals(0, tune.level)
        assertEquals(60f, tune.pace)
        assertTrue(changes.isEmpty())
    }

    @Test fun `quality first halves the frame rate before touching quality`() {
        // 20 ms a frame at full quality: 50 fps, short of 60, easily 30.
        val tune = Autotune(0, 60f, qualityFirst = true)
        val changes = run(tune, 30.0) { l -> 20.0 * l.scale * l.scale }
        assertEquals(30f, tune.pace)
        assertEquals(Autotune.LEVELS.first(), tune.current)
        assertEquals(3, changes.size) // down to 30; one probe back up to 60, which fails
    }

    @Test fun `quality first keeps more quality than holding 60 would`() {
        val tune = Autotune(0, 60f, qualityFirst = true)
        run(tune, 60.0, costMs = slowGpu)
        // At 30, 0.72 costs ~34 ms (29 fps, close enough); 0.85 can't. Holding 60 settles at 0.42.
        assertEquals(30f, tune.pace)
        assertEquals(0.72f, tune.current.scale)
    }

    @Test fun `quality first leaves a CPU-bound preset its quality at 30`() {
        val tune = Autotune(0, 60f, qualityFirst = true)
        val changes = run(tune, 20.0, cpuMs = 20.0) { 4.0 }
        assertEquals(30f, tune.pace)
        assertEquals(Autotune.LEVELS.first(), tune.current)
        assertTrue(changes.none { it.second == Autotune.Change.TOO_HEAVY })
    }

    @Test fun `quality first has nothing to halve at 30`() {
        assertEquals(Autotune.LEVELS.map { Autotune.Rung(it, 30f) }, Autotune.ladderFor(30f, qualityFirst = true))
        assertEquals(Autotune.LEVELS.size + 1, Autotune.ladderFor(60f, qualityFirst = true).size)
    }
}
