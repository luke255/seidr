package eu.bradshaw.seidr.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The scrubber's bar: a wavy line up to the playhead and a flat one after it,
 * like Android's own media controls. The wave rolls while [playing] and settles
 * flat on pause.
 *
 * It's all drawn, so the playhead lands between pixels and glides with the song
 * (a Slider places its thumb at whole pixels, so it steps). [fraction] is read
 * only while drawing: per-frame updates redraw it without recomposing anything.
 */
@Composable
fun WavyScrubber(
    fraction: () -> Float,
    playing: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.secondaryContainer
    val wave by animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "wave")
    var phase by remember { mutableFloatStateOf(0f) } // dp, within one wavelength
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) phase = (phase + (now - last) / 1e9f * SPEED_DP_S) % WAVELENGTH.value
            last = now
        }
    }
    // For accessibility, in whole percent so it doesn't change every frame.
    val percent by remember { derivedStateOf { (fraction().coerceIn(0f, 1f) * 100).roundToInt() } }
    val path = remember { Path() }

    Canvas(
        modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); finish(); true }
            }
            .focusable()
            .fillMaxWidth()
            .height(HEIGHT)
            .pointerInput(Unit) {
                detectTapGestures { change(fractionAt(it.x, size.width.toFloat())); finish() }
            }
            .pointerInput(Unit) {
                val width = { size.width.toFloat() }
                detectHorizontalDragGestures(
                    onDragStart = { change(fractionAt(it.x, width())) },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { e, _ -> e.consume(); change(fractionAt(e.position.x, width())) }
            },
    ) {
        val stroke = STROKE.toPx()
        val left = INSET.toPx()
        val right = size.width - left
        val x = left + (right - left) * fraction().coerceIn(0f, 1f)
        val cy = size.height / 2
        val thumbW = THUMB_WIDTH.toPx()
        // Where the lines stop either side of the thumb, allowing for their round caps.
        val reach = thumbW / 2 + GAP.toPx() + stroke / 2

        val from = x + reach
        if (from < right) drawLine(inactive, Offset(from, cy), Offset(right, cy), stroke, StrokeCap.Round)

        val to = x - reach
        if (to > left) {
            val amp = AMPLITUDE.toPx() * wave
            if (amp < 0.1f) {
                drawLine(active, Offset(left, cy), Offset(to, cy), stroke, StrokeCap.Round)
            } else {
                val length = WAVELENGTH.toPx()
                val shift = phase.dp.toPx()
                val taper = length / 2 // the wave grows from flat over this at each end
                path.rewind()
                path.moveTo(left, cy)
                var px = left
                while (px < to) {
                    px = min(px + SAMPLE_PX, to)
                    val t = (min(px - left, to - px) / taper).coerceIn(0f, 1f)
                    val ease = t * t * (3 - 2 * t)
                    path.lineTo(px, cy + amp * ease * sin(2 * PI.toFloat() * (px - shift) / length))
                }
                drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        val thumbH = THUMB_HEIGHT.toPx()
        drawRoundRect(active, Offset(x - thumbW / 2, cy - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
    }
}

private fun androidx.compose.ui.unit.Density.fractionAt(x: Float, width: Float): Float {
    val left = INSET.toPx()
    return ((x - left) / (width - 2 * left).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

private val HEIGHT = 44.dp
private val STROKE = 4.dp
private val INSET = 2.dp
private val GAP = 4.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_HEIGHT = 20.dp
private val AMPLITUDE = 3.dp
private val WAVELENGTH = 32.dp
private const val SPEED_DP_S = 12f
private const val SAMPLE_PX = 2f
