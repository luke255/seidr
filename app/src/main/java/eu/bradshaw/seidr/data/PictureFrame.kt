package eu.bradshaw.seidr.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where a picture sits in a square frame of side [side]: drawn at [scale]
 * (frame units per picture pixel) with its centre [x], [y] from the frame's
 * centre. Pure, so `PictureFrameTest` pins the rules down.
 *
 * A picture never leaves a gap it could cover, and never pokes out of the
 * frame where it doesn't cover it: along each side, a picture bigger than
 * the frame keeps it covered, and a smaller one stays inside it.
 */
data class PictureFrame(val width: Int, val height: Int, val side: Float, val scale: Float, val x: Float = 0f, val y: Float = 0f) {
    /** Covering the whole square, cropping the long side. */
    val fillScale get() = side / min(width, height)
    /** The whole picture, with a gap along the long side. */
    val fitScale get() = side / max(width, height)

    /** Centred, covering the frame. */
    fun fill() = copy(scale = fillScale, x = 0f, y = 0f)

    /** Centred, all of it showing. */
    fun fit() = copy(scale = fitScale, x = 0f, y = 0f)

    val isFill get() = near(scale, fillScale) && x == 0f && y == 0f
    val isFit get() = near(scale, fitScale) && x == 0f && y == 0f

    /** Whether some of the frame is left uncovered (and needs a background). */
    val hasGap get() = width * scale < side - 0.5f || height * scale < side - 0.5f

    /** Zoomed by [zoom] about [px], [py] (from the frame's centre), moved by [dx], [dy], then kept in bounds. */
    fun moved(dx: Float, dy: Float, zoom: Float = 1f, px: Float = 0f, py: Float = 0f): PictureFrame {
        val s = (scale * zoom).coerceIn(fitScale, fillScale * MAX_ZOOM)
        val k = s / scale
        return copy(scale = s, x = (x - px) * k + px + dx, y = (y - py) * k + py + dy).bounded()
    }

    /** Kept in bounds along each side (see the class notes). */
    fun bounded(): PictureFrame {
        fun clamp(c: Float, size: Float): Float {
            val room = abs(size - side) / 2
            return c.coerceIn(-room, room)
        }
        val s = scale.coerceIn(fitScale, fillScale * MAX_ZOOM)
        return copy(scale = s, x = clamp(x, width * s), y = clamp(y, height * s))
    }

    /**
     * Where a let-go settles: close to filling or fitting, exactly that; close to
     * the middle, centred. Otherwise as it is.
     */
    fun snapped(): PictureFrame {
        val s = when {
            near(scale, fillScale) -> fillScale
            near(scale, fitScale) -> fitScale
            else -> scale
        }
        fun centre(c: Float) = if (abs(c) < side * SNAP_CENTRE) 0f else c
        return copy(scale = s, x = centre(x), y = centre(y)).bounded()
    }

    /** This frame for a square of side [to] (the output image), same placement. */
    fun resized(to: Float): PictureFrame {
        val k = to / side
        return copy(side = to, scale = scale * k, x = x * k, y = y * k)
    }

    companion object {
        /** How far in past filling a picture can be zoomed. */
        const val MAX_ZOOM = 4f
        /** Within this much of filling or fitting (as a ratio), a let-go snaps to it. */
        const val SNAP_SCALE = 0.06f
        /** Within this much of the middle (of the side), a let-go centres. */
        const val SNAP_CENTRE = 0.04f

        private fun near(a: Float, b: Float) = abs(a / b - 1f) < SNAP_SCALE

        /** A picture first shows filling the frame, the usual crop for a cover. */
        fun start(width: Int, height: Int, side: Float) = PictureFrame(width, height, side, 1f).fill()
    }
}
