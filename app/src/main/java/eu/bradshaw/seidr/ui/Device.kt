package eu.bradshaw.seidr.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.staticCompositionLocalOf

/** Android TV (Leanback): driven by a remote, no touch, no downloads. */
fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** [isTv], provided once at the root so screens can drop what a TV doesn't need. */
val LocalIsTv = staticCompositionLocalOf { false }
