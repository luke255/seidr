package eu.bradshaw.seidr.vis

/** libprojectM, through `milkdrop_jni.cpp`. Every call must be on the GL thread. */
object ProjectM {
    const val SWITCH_REQUESTED = 1
    const val LOAD_FAILED = 2
    const val HARD_CUT = 4

    init {
        System.loadLibrary("projectM-4")
        System.loadLibrary("milkdrop")
    }

    @JvmStatic external fun nativeCreate(textureDir: String, width: Int, height: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeResize(handle: Long, width: Int, height: Int)
    @JvmStatic external fun nativeConfigure(
        handle: Long, presetSeconds: Double, blendSeconds: Double, meshW: Int, meshH: Int, hardCuts: Boolean,
    )
    @JvmStatic external fun nativeLoadPreset(handle: Long, data: String, smooth: Boolean)
    @JvmStatic external fun nativeAddPcm(handle: Long, samples: FloatArray, frames: Int)
    /** Renders one frame; returns [SWITCH_REQUESTED] (with [HARD_CUT] on a beat) / [LOAD_FAILED] bits for events since the last call. */
    @JvmStatic external fun nativeRender(handle: Long): Int
}
