package eu.bradshaw.seidr.vis.xr

import android.app.Activity

/**
 * The OpenXR session, through `xr_jni.cpp`: part of a sphere around the
 * viewer showing projectM's frames. Every call on the one render thread,
 * which [nativeCreate] makes the EGL context current on.
 */
object Xr {
    const val INPUT_PLAY_PAUSE = 1
    const val INPUT_NEXT = 2
    const val INPUT_PREVIOUS = 4
    const val INPUT_EXIT = 8
    const val INPUT_NEXT_PRESET = 16

    /** From [nativeBeginFrame]: the session isn't running; try again shortly. */
    const val IDLE = -1L
    /** From [nativeBeginFrame]: the session is over. */
    const val QUIT = -2L

    init {
        System.loadLibrary("seidrxr")
    }

    /** A session rendering at up to [width] x [height], or 0 (logged under MilkdropXr) if the headset can't. */
    @JvmStatic external fun nativeCreate(activity: Activity, width: Int, height: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    /** The next frame's predicted display time (ns), [IDLE] or [QUIT]. A display time must be followed by [nativeEndFrame]. */
    @JvmStatic external fun nativeBeginFrame(handle: Long): Long
    /** INPUT_* bits since the last call. */
    @JvmStatic external fun nativeInput(handle: Long): Int
    /** projectM has drawn [width] x [height] into the offscreen surface: show it. */
    @JvmStatic external fun nativePresent(handle: Long, width: Int, height: Int)
    @JvmStatic external fun nativeEndFrame(handle: Long)
}
