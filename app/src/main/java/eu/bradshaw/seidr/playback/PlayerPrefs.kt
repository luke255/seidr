package eu.bradshaw.seidr.playback

import android.content.Context

/** Shuffle is Seiðr's own (it reorders the queue), so its flag is stored here, not in ExoPlayer. */
object PlayerPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
    fun shuffle(c: Context) = prefs(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, v: Boolean) = prefs(c).edit().putBoolean("shuffle", v).apply()
}
