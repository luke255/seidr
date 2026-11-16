package eu.bradshaw.seidr.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import eu.bradshaw.seidr.CoverArt
import eu.bradshaw.seidr.ui.theme.ArtColours
import eu.bradshaw.seidr.ui.theme.extractArtColours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colours per cover id; a null [colours] means monochrome art. Failed loads aren't cached. */
private class Picked(val colours: ArtColours?)

private val cache = LruCache<String, Picked>(200)

/** Same model as the big artwork, so this reads Coil's disk cache rather than the network. */
private const val ART_SIZE = 900
/** The main analysis runs at 112px; thin coloured text (Adele 21) needs 224px. */
private const val SAMPLE_PX = 112
private const val FINE_PX = 224

private suspend fun load(context: Context, id: String): Picked? {
    cache.get(id)?.let { return it }
    val request = ImageRequest.Builder(context)
        .data(CoverArt(id, ART_SIZE))
        .size(FINE_PX)
        .allowHardware(false)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        val fine = result.image.toBitmap()
        val small = Bitmap.createScaledBitmap(fine, SAMPLE_PX, SAMPLE_PX * fine.height / fine.width, true)
        Picked(extractArtColours(small.pixels(), fine.pixels())).also { cache.put(id, it) }
    }
}

private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/**
 * Colours for the cover [id], or null (no art, monochrome art, or not loaded).
 * While a new cover loads, the previous colours stay, so skipping tracks
 * doesn't flash back to the app theme.
 */
@Composable
fun rememberArtColours(id: String?): ArtColours? {
    val context = LocalContext.current
    var colours by remember { mutableStateOf(id?.let { cache.get(it)?.colours }) }
    LaunchedEffect(id) {
        colours = if (id == null) null else load(context.applicationContext, id)?.colours
    }
    return colours
}

/**
 * How long Now Playing takes to move to a new track: the colours and the cover's
 * slide both finish this long after the last change, eased out so a change
 * mid-way carries on without a stall.
 */
internal const val ART_CHANGE_MS = 1320

/**
 * True for a moment after the screen comes back into view (app resumed, screen
 * turned on). A track change that shows up then happened while nobody was
 * looking, so Now Playing shows the new cover and colours as they are instead
 * of animating to them. Not true on first show: that keeps its usual fade.
 */
@Composable
internal fun rememberJustBack(): () -> Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { longArrayOf(0L, 0L) } // [stopped (0/1), back at]
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> state[0] = 1
                Lifecycle.Event.ON_START -> if (state[0] == 1L) { state[0] = 0; state[1] = SystemClock.uptimeMillis() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return { state[1] != 0L && SystemClock.uptimeMillis() - state[1] < JUST_BACK_MS }
}

/** Long enough for the new cover's colours to load from Coil's caches. */
private const val JUST_BACK_MS = 1500

/** A [SchemeFade]'s timing: [ART_CHANGE_MS], or a snap when the change happened while the screen was away ([rememberJustBack]). */
internal fun artFadeProgress(justBack: Boolean): AnimationSpec<Float> =
    if (justBack) snap() else tween(ART_CHANGE_MS, easing = EaseOutCubic)
