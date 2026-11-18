package eu.bradshaw.seidr.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.bradshaw.seidr.R
import eu.bradshaw.seidr.data.PictureFrame
import eu.bradshaw.seidr.ui.artGradient
import eu.bradshaw.seidr.ui.theme.ArtScheme
import eu.bradshaw.seidr.ui.theme.artScheme
import eu.bradshaw.seidr.ui.theme.extractArtColours
import eu.bradshaw.seidr.ui.theme.monochromeArtScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A picture chosen for a playlist, with the colours (Now Playing's) for any gap around it. */
class PickedPicture(val bitmap: Bitmap, val look: ArtScheme) {
    /** Top to bottom, as album pages and Now Playing draw their background. */
    val gradient = listOf(look.wash, lerp(look.scheme.background, look.wash, 0.6f), look.scheme.background)
}

/** Reads the picture at [uri], no bigger than it needs to be. Throws [IOException] if it isn't one. */
suspend fun loadPicture(context: Context, uri: Uri): PickedPicture = withContext(Dispatchers.Default) {
    val bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Room to zoom in a little past filling the output, without holding a 50 MP photo.
            var sample = 1
            while (minOf(info.size.width, info.size.height) / (sample * 2) >= PICTURE_SIZE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        throw IOException("not a picture", e)
    }
    // The same colour picking as Now Playing: 112px, and 224px for thin lines of colour.
    fun pixels(px: Int): IntArray {
        val b = Bitmap.createScaledBitmap(bitmap, px, (px * bitmap.height / bitmap.width).coerceAtLeast(1), true)
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }
    val look = extractArtColours(pixels(112), pixels(224))?.let(::artScheme) ?: monochromeArtScheme
    PickedPicture(bitmap, look)
}

/** [picture] placed as [frame] in a [PICTURE_SIZE] square, any gap filled with its gradient, as a JPEG. */
suspend fun renderPicture(picture: PickedPicture, frame: PictureFrame): ByteArray = withContext(Dispatchers.Default) {
    val size = PICTURE_SIZE.toFloat()
    val f = frame.resized(size)
    val out = Bitmap.createBitmap(PICTURE_SIZE, PICTURE_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    if (f.hasGap) {
        val colours = picture.gradient.map { it.toArgb() }.toIntArray()
        canvas.drawRect(0f, 0f, size, size, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, size, colours, null, Shader.TileMode.CLAMP)
        })
    }
    val m = Matrix().apply {
        postTranslate(-picture.bitmap.width / 2f, -picture.bitmap.height / 2f)
        postScale(f.scale, f.scale)
        postTranslate(size / 2 + f.x, size / 2 + f.y)
    }
    canvas.drawBitmap(picture.bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    ByteArrayOutputStream().use { buf ->
        out.compress(Bitmap.CompressFormat.JPEG, 90, buf)
        buf.toByteArray()
    }
}

/** The side of a playlist picture as it's uploaded. */
const val PICTURE_SIZE = 1200

/**
 * Framing a new playlist picture: pinch and drag, or Fill (covering the square)
 * and Fit (all of it, on the picture's own colours). A let-go near either snaps
 * to it, centred.
 */
@Composable
fun PictureCropDialog(picture: PickedPicture, onSave: (PictureFrame) -> Unit, onDismiss: () -> Unit) {
    val image = remember(picture) { picture.bitmap.asImageBitmap() }
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<PictureFrame?>(null) }
    // A snap or a button glides there rather than jumping.
    val glide = remember { Animatable(1f) }
    var from by remember { mutableStateOf<PictureFrame?>(null) }
    fun shown(): PictureFrame? {
        val to = frame ?: return null
        val a = from ?: return to
        val t = glide.value
        return to.copy(scale = a.scale + (to.scale - a.scale) * t, x = a.x + (to.x - a.x) * t, y = a.y + (to.y - a.y) * t)
    }
    fun goTo(target: PictureFrame) {
        from = shown()
        frame = target
        scope.launch { glide.snapTo(0f); glide.animateTo(1f, spring(stiffness = 400f)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_picture_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val side = with(LocalDensity.current) { maxWidth.toPx() }
                    if (frame?.side != side) frame = PictureFrame.start(picture.bitmap.width, picture.bitmap.height, side)
                    Canvas(
                        Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().pointerInput(picture, side) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                from = null
                                do {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val c = event.calculateCentroid()
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        frame = frame?.moved(pan.x, pan.y, zoom, c.x - side / 2, c.y - side / 2)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                frame?.let { f -> f.snapped().takeIf { it != f }?.let(::goTo) }
                            }
                        },
                    ) {
                        val f = shown() ?: return@Canvas
                        if (f.hasGap) drawRect(artGradient(picture.look.wash, picture.look.scheme.background))
                        withTransform({
                            translate(side / 2 + f.x, side / 2 + f.y)
                            scale(f.scale, f.scale, Offset.Zero)
                            translate(-image.width / 2f, -image.height / 2f)
                        }) {
                            drawImage(image, IntOffset.Zero, IntSize(image.width, image.height))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(frame?.isFill == true, { frame?.let { goTo(it.fill()) } }, { Text(stringResource(R.string.playlist_picture_fill)) })
                    FilterChip(frame?.isFit == true, { frame?.let { goTo(it.fit()) } }, { Text(stringResource(R.string.playlist_picture_fit)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { frame?.let(onSave) }) { Text(stringResource(R.string.playlist_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) } },
    )
}
