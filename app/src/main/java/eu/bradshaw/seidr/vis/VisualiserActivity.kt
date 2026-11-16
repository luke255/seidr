package eu.bradshaw.seidr.vis

import eu.bradshaw.seidr.R
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import eu.bradshaw.seidr.Graph
import eu.bradshaw.seidr.vis.xr.XrVisualiserActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The MilkDrop visualiser, full screen: projectM on the bundled presets in a
 * shuffled run (a new one every 30 s, or on a big beat), fed what's audible by
 * [VisAudio] and held at the display's frame rate (up to 60) by [Autotune]. A
 * device that can't run most presets at 60 even at the lowest quality drops to
 * 30 for good (see [VisEngine]).
 *
 * Over it, [VisOverlay]: the playing track in a corner. Playback is all
 * gestures, each confirmed by a passing sign, with or without a page up: a
 * tap (select on a remote) plays/pauses, sideways swipes (left/right) skip
 * tracks. Swipes up and down (the D-pad's too) turn through the [VisPage]s:
 * the preset's name with a flag (hides it for good), a heart (loved presets
 * come up about one time in ten) and back/forward through presets, Now
 * Playing's transport, the track, lyrics, the queue and the logo. Back puts a
 * page away; pages stay until then. A hold opens the preset's page. With [EXTRA_PREVIEW] it shows just that one preset, for
 * Settings → Visualiser. (Now Playing shows [VisWarning] before the first.)
 *
 * Its own activity (opened from Now Playing): GL lifecycles and immersive
 * mode are simpler here than in Compose. The overlay is ordinary views over
 * the GL surface, composited by the display hardware, so it costs the
 * visualiser no frames. What it shows is [VisEngine]'s, shared with the
 * headset's [XrVisualiserActivity]. Settings → Visualiser can show the
 * tuner's readout in a corner; logcat tag Milkdrop.
 */
class VisualiserActivity : ComponentActivity() {
    private val graph by lazy { Graph.get(this) }
    private val prefs by lazy { graph.visPrefs }
    private var preview: String? = null
    private lateinit var view: GLSurfaceView
    @Volatile private var hud: TextView? = null
    private lateinit var renderer: Renderer

    private var page by mutableStateOf<VisPage?>(null)
    private var message by mutableStateOf<String?>(null)
    /** The preset on screen, for the overlay's heart and flag. */
    private var preset by mutableStateOf<String?>(null)
    /** The last play/pause, for the overlay's passing sign. */
    private var pulse by mutableStateOf<Pulse?>(null)
    private val closePage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showPage(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getStringExtra(EXTRA_PREVIEW)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Phones keep the navigation bar (see-through, drawn over): with it hidden, the
        // first back swipe only reveals it, and only a second goes back. TVs have none.
        val tv = eu.bradshaw.seidr.ui.isTv(this)
        window.isNavigationBarContrastEnforced = false
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(if (tv) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, closePage)
        if (preview == null) closeWhenGuestLeaves(graph)

        if (prefs.forgetPacingBug(display.refreshRate)) Log.i(TAG, "forgot what the tuner learned under the old pacing")
        // MilkDrop presets are tuned for ~60 fps; never aim past the panel either.
        val target = minOf(prefs.targetFps, display.refreshRate)
        frameNs = (1e9 / target).toLong()
        val tune = Autotune(prefs.typicalLevel, target)
        renderer = Renderer(VisEngine(this, prefs, VisEngine.presets(this, prefs, preview), preview, tune, host))
        Log.i(TAG, "target $target fps, starting at level ${tune.level}, ${prefs.hiddenPaths.size} presets hidden")

        view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            // Paced (see [pace]): presets feed each frame into the next, so past
            // 60 fps they decay and zoom too fast.
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    // Lets a 120 Hz panel drop to 60 while this shows.
                    h.surface.setFrameRate(renderer.targetFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
        applyScale(tune.current.scale)
        val overlay = ComposeView(this).apply {
            setContent {
                VisOverlay(
                    graph = graph,
                    preview = preview,
                    preset = preset,
                    page = page,
                    message = message,
                    onTurn = { by -> showPage(VisPage.turn(page, by)) },
                    onPage = ::showPage,
                    onHidePreset = ::hideCurrentPreset,
                    onPresetStep = ::stepPreset,
                    onMessageShown = { message = null },
                    pulse = pulse,
                    onPlayPause = ::playPause,
                    onSkip = ::skip,
                    onClose = ::finish,
                )
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
            addView(overlay)
            // The tuner's readout, if Settings → Visualiser asks for it. Top left,
            // clear of the track card (bottom left), and hidden while a page
            // shows, since some use the top.
            val readout = TextView(context).apply {
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
                textSize = 12f
                setPadding(48, 48, 48, 0)
            }
            addView(readout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
            ))
            lifecycleScope.launch {
                prefs.showStats.collect { on ->
                    readout.visibility = if (on) View.VISIBLE else View.GONE
                    hud = readout.takeIf { on }
                }
            }
        })
    }

    private fun showPage(to: VisPage?) {
        page = to
        renderer.engine.logo = to == VisPage.LOGO
        hud?.animate()?.alpha(if (to != null) 0f else 1f)?.setDuration(200)
        closePage.isEnabled = to != null
    }

    /** Hides the playing preset for good (Settings → Visualiser undoes it) and moves on. */
    private fun hideCurrentPreset() {
        if (preview != null) return
        view.queueEvent { renderer.engine.hideCurrent() }
        message = getString(R.string.vis_preset_hidden)
    }

    /** Back to the last preset, or on to the next, cut to straight away. */
    private fun stepPreset(forward: Boolean) {
        if (preview != null) return
        view.queueEvent { if (forward) renderer.engine.next(hardCut = true) else renderer.engine.previous() }
    }

    /** Select is ours (play/pause, held: the preset's page) unless a page with buttons is up. */
    private val selectIsOurs get() = page?.controls != true && preview == null

    /**
     * Remote, ahead of the overlay: up and down turn the pages. Left and
     * right skip tracks, except on a page with buttons, where they move
     * between those. Back puts a page away.
     */
    private fun pageKey(event: KeyEvent): Boolean {
        if (preview != null) return false
        val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (first) showPage(VisPage.turn(page, if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1))
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (page?.controls == true) return false
                if (first) skip(next = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Decided on release: a hold shows the preset's page instead (onKeyLongPress).
        if (keyCode in SELECT && selectIsOurs) { event.startTracking(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in SELECT && selectIsOurs) { if (page == null) showPage(VisPage.PRESET); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // A release after a hold comes marked cancelled.
        if (keyCode in SELECT && event.isTracking && !event.isCanceled && selectIsOurs) {
            playPause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Plays or pauses, with a passing sign so the press shows. */
    private fun playPause() {
        val pausing = graph.player.ui.value.showPause
        graph.player.playPause()
        flash(if (pausing) Pulse.Kind.PAUSE else Pulse.Kind.PLAY)
    }

    /** Skips a track, with a passing sign. */
    private fun skip(next: Boolean) {
        if (next) graph.player.next() else graph.player.previous()
        flash(if (next) Pulse.Kind.NEXT else Pulse.Kind.PREVIOUS)
    }

    private fun flash(kind: Pulse.Kind) {
        val p = Pulse(kind)
        pulse = p
        // Gone once shown, so it can't flash again later.
        lifecycleScope.launch { delay(2_000); if (pulse === p) pulse = null }
    }

    // Lint reads the override as a call into androidx-internal API; it is the platform method.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = pageKey(event) || super.dispatchKeyEvent(event)

    /** Renders below the window's size; the display's scaler stretches it back for free. */
    private fun applyScale(scale: Float) = runOnUiThread {
        val bounds = windowManager.currentWindowMetrics.bounds
        if (scale >= 1f) view.holder.setSizeFromLayout()
        else view.holder.setFixedSize((bounds.width() * scale).toInt(), (bounds.height() * scale).toInt())
    }

    private var nextFrameNs = 0L
    @Volatile private var frameNs = 16_666_667L // at the target frame rate
    private var pacing = false
    private val pace = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!pacing) return
            // Deadlines a frame apart, each met by the first vsync at (or just short of, for
            // jitter) it. Counting from the last frame instead rounds every frame up to whole
            // vsyncs: 60 on a 72 Hz panel (the Quest's) came out at 36.
            if (frameTimeNanos >= nextFrameNs - 1_700_000L) {
                // Far behind (just resumed, or a stall): start again from now, don't catch up.
                nextFrameNs = if (frameTimeNanos - nextFrameNs > frameNs) frameTimeNanos + frameNs else nextFrameNs + frameNs
                view.requestRender()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onStart() {
        super.onStart()
        graph.player.connect()
    }

    override fun onResume() {
        super.onResume()
        VisAudio.active = true
        view.onResume()
        pacing = true
        Choreographer.getInstance().postFrameCallback(pace)
    }

    override fun onPause() {
        VisAudio.active = false
        pacing = false
        Choreographer.getInstance().removeFrameCallback(pace)
        view.queueEvent {
            renderer.engine.remember()
            if (isFinishing) renderer.engine.release()
        }
        view.onPause()
        super.onPause()
    }

    /** What only this activity can do for the engine: scale the surface, show the preset, pace. */
    private val host = object : VisEngine.Host {
        override fun applyScale(scale: Float) = this@VisualiserActivity.applyScale(scale)
        override fun presetShown(path: String) = runOnUiThread { preset = path }
        override fun retargeted(fps: Float) {
            frameNs = (1e9 / fps).toLong()
            runOnUiThread { view.holder.surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    /** The GL surface's side of [VisEngine], plus the readout. Everything here runs on the GL thread. */
    private inner class Renderer(val engine: VisEngine) : GLSurfaceView.Renderer {
        private var lastHud = 0L
        val targetFps get() = engine.tune.targetFps

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = engine.create(VisEngine.textureDir(this@VisualiserActivity))

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = engine.resize(width, height)

        override fun onDrawFrame(gl: GL10?) {
            val now = SystemClock.elapsedRealtimeNanos()
            engine.frame(now)
            val hud = hud ?: return
            if (now - lastHud > 250_000_000L) {
                lastHud = now
                val text = engine.stats()
                runOnUiThread { hud.text = text }
            }
        }
    }

    companion object {
        private const val TAG = VisEngine.TAG
        private val SELECT = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

        /** Shows just this preset (its path in the pack), for Settings → Visualiser. */
        const val EXTRA_PREVIEW = "preview"

        fun preview(context: Context, path: String) =
            context.startActivity(Intent(context, VisualiserActivity::class.java).putExtra(EXTRA_PREVIEW, path))
    }
}

/** A play/pause or skip just done: [kind] says which sign to flash. Distinct each time, so a repeat still shows. */
class Pulse(val kind: Kind) {
    enum class Kind { PLAY, PAUSE, NEXT, PREVIOUS }
}
