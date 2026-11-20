package eu.bradshaw.seidr.vis.xr

import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.vis.closeWhenGuestLeaves
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.vis.Autotune
import eu.bradshaw.seidr.vis.VisAudio
import eu.bradshaw.seidr.vis.VisEngine
import eu.bradshaw.seidr.vis.VisualiserActivity

/**
 * The visualiser all around you, on a headset (Quest): an immersive OpenXR
 * activity in the same app as the 2D panels, opened from Now Playing
 * (beside the visualiser). [VisEngine] decides what's shown, exactly as on a screen; [Xr]
 * puts each frame on part of a sphere around the viewer, 150° by 100° and
 * infinitely far away, so the eyes rest parallel. The headset's compositor draws it at the display's rate, so
 * head movement stays smooth whatever a preset costs; projectM itself is
 * paced to 60.
 *
 * Controllers: A/X or a trigger plays/pauses, a thumbstick skips, a grip
 * moves on to the next preset, B/Y leaves.
 * Hands: a pinch plays/pauses. The system menu can always quit.
 *
 * Everything GL runs on its own render thread, which owns the session.
 */
class XrVisualiserActivity : ComponentActivity() {
    private val graph by lazy { Graph.get(this) }
    private val prefs by lazy { graph.visPrefs }
    @Volatile private var running = true
    private lateinit var thread: Thread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!graph.presetPack.ready) {
            Toast.makeText(this, getString(R.string.vis_pack_not_ready), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread = Thread(::run, "XrVisualiser").apply { start() }
        closeWhenGuestLeaves(graph)
    }

    override fun onStart() {
        super.onStart()
        graph.player.connect()
    }

    override fun onDestroy() {
        running = false
        if (::thread.isInitialized) thread.join(2_000)
        super.onDestroy()
    }

    private fun run() {
        val xr = Xr.nativeCreate(this, WIDTH, HEIGHT)
        if (xr == 0L) {
            // Back to the flat visualiser, rather than a dead end.
            runOnUiThread {
                Toast.makeText(this, getString(R.string.vis_xr_failed), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, VisualiserActivity::class.java))
                finish()
            }
            return
        }
        val target = minOf(prefs.targetFps, 60f)
        var frameNs = (1e9 / target).toLong()
        val quality = prefs.qualityFirst.value
        val tune = Autotune(prefs.typicalLevel(quality), target, quality)
        lateinit var engine: VisEngine
        engine = VisEngine(this, prefs, VisEngine.presets(this, prefs, null), null, tune, object : VisEngine.Host {
            override fun applyScale(scale: Float) = engine.resize((WIDTH * scale).toInt(), (HEIGHT * scale).toInt())
            override fun presetShown(path: String) {}
            override fun retargeted(fps: Float) { frameNs = (1e9 / fps).toLong() }
            override fun pace(fps: Float) { frameNs = (1e9 / fps).toLong() }
        })
        Log.i(TAG, "immersive: target $target fps${if (quality) ", quality first" else ""}, starting at level ${tune.level}")
        engine.create(VisEngine.textureDir(this))
        engine.resize((WIDTH * tune.current.scale).toInt(), (HEIGHT * tune.current.scale).toInt())
        var nextFrameNs = 0L
        var lastStats = 0L
        try {
            while (running) {
                val time = Xr.nativeBeginFrame(xr)
                if (time == Xr.QUIT) break
                if (time == Xr.IDLE) {
                    VisAudio.active = false
                    SystemClock.sleep(20)
                    continue
                }
                VisAudio.active = true
                val input = Xr.nativeInput(xr)
                if (input and Xr.INPUT_NEXT_PRESET != 0) engine.next(hardCut = true)
                handle(input)
                // Deadlines a frame apart, as on a screen (see VisualiserActivity.pace).
                if (time >= nextFrameNs - 1_700_000L) {
                    nextFrameNs = if (time - nextFrameNs > frameNs) time + frameNs else nextFrameNs + frameNs
                    val now = SystemClock.elapsedRealtimeNanos()
                    engine.frame(now)
                    Xr.nativePresent(xr, engine.width, engine.height)
                    if (now - lastStats > 5_000_000_000L) {
                        lastStats = now
                        Log.i(TAG, engine.stats())
                    }
                }
                Xr.nativeEndFrame(xr)
            }
        } finally {
            VisAudio.active = false
            engine.remember()
            engine.release()
            Xr.nativeDestroy(xr)
        }
        runOnUiThread { if (!isFinishing) finish() }
    }

    /** Controller and hand input, on the main thread (the player's). */
    private fun handle(input: Int) {
        if (input == 0) return
        runOnUiThread {
            val player = graph.player
            if (input and Xr.INPUT_PLAY_PAUSE != 0) player.playPause()
            if (input and Xr.INPUT_NEXT != 0) player.next()
            if (input and Xr.INPUT_PREVIOUS != 0) player.previous()
            if (input and Xr.INPUT_EXIT != 0) finish()
        }
    }

    companion object {
        private const val TAG = "MilkdropXr"
        // Full quality: 20 pixels a degree over the sphere's 150° x 100° (the
        // same aspect, so nothing stretches; see xr_jni.cpp). The display
        // shows about 25. The tuner renders a share of it when a preset needs.
        private const val WIDTH = 3000
        private const val HEIGHT = 2000

        /** A standalone headset (Quest), which can run this. */
        fun available(context: Context) = context.packageManager.hasSystemFeature("oculus.hardware.standalone_vr")
    }
}
