package eu.bradshaw.seidr.vis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.core.content.ContextCompat
import eu.bradshaw.seidr.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's ð over the preset, in the spirit of MilkDrop's sprites: it
 * drifts, sways and swells on the bass. It's made of the preset's own light:
 * where it sits, the picture beneath is brightened and warmed towards the
 * icon's gold, so it takes the preset's colours and textures (and all but
 * vanishes where the preset is dark), and a gold glow flares in on the beats.
 * (MilkDrop could also burn a sprite into the next frame's feedback; projectM
 * has no way in for that without changing it.)
 *
 * GL thread only. [create] in each new context, then [draw] straight after
 * projectM's frame, into the same framebuffer.
 */
class LogoSprite(private val context: Context) {
    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var texture = 0
    private var uRect = 0
    private var uRot = 0
    private var uColour = 0
    private var uOpacity = 0

    /** How shown it is, 0..1, easing towards the page's wish. */
    private var shown = 0f
    private var lastNs = 0L
    private var t = 0.0

    // Bass: a low-passed signal's energy against its own slow average.
    private var lp = 0f
    private var slow = 1e-4f
    private var beat = 0f

    fun create() {
        program = link(VERTEX, FRAGMENT)
        uRect = GLES30.glGetUniformLocation(program, "rect")
        uRot = GLES30.glGetUniformLocation(program, "rot")
        uColour = GLES30.glGetUniformLocation(program, "colour")
        uOpacity = GLES30.glGetUniformLocation(program, "opacity")

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glGenTextures(1, ids, 0); texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val bitmap = glyph()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Draws it, if it's [wanted] or still fading out, over a [width] x [height]
     * frame. [pcm] is this frame's audio ([frames] stereo frames).
     */
    fun draw(wanted: Boolean, now: Long, width: Int, height: Int, pcm: FloatArray, frames: Int) {
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        shown = if (wanted) (shown + dt / FADE_S).coerceAtMost(1f) else (shown - dt / FADE_S).coerceAtLeast(0f)
        listen(pcm, frames, dt)
        if (shown <= 0f || program == 0 || width <= 0 || height <= 0) return
        t += dt

        // Drifting about the middle, swaying, and swelling on the bass.
        val size = SIZE * (1f + 0.18f * beat) * (0.85f + 0.15f * shown)
        val aspect = height.toFloat() / width
        val x = 0.05f * sin(t * 0.31).toFloat()
        val y = 0.04f * sin(t * 0.47 + 1.0).toFloat()
        val rot = 0.07f * sin(t * 0.23).toFloat()
        val glow = GLOW + BEAT_GLOW * beat

        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(uRect, x, y, size * aspect, size)
        GLES30.glUniform1f(uRot, rot)
        GLES30.glUniform1f(uOpacity, shown)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glBindVertexArray(vao)
        // The preset's light, lifted: what's there times (1 + gold).
        GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE)
        GLES30.glUniform3f(uColour, GOLD_R * LIFT, GOLD_G * LIFT, GOLD_B * LIFT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // And a little gold of its own, more on the beat.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUniform3f(uColour, GOLD_R * glow, GOLD_G * glow, GOLD_B * glow)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Follows the bass: [beat] jumps on a kick and decays over a few tenths of a second. */
    private fun listen(pcm: FloatArray, frames: Int, dt: Float) {
        if (frames > 0) {
            var sum = 0f
            for (i in 0 until frames) {
                val mono = (pcm[2 * i] + pcm[2 * i + 1]) * 0.5f
                lp += (mono - lp) * LOW_PASS
                sum += lp * lp
            }
            val energy = sqrt(sum / frames)
            slow += (energy - slow) * (dt / 2f).coerceIn(0f, 1f)
            val level = ((energy / slow.coerceAtLeast(1e-4f)) - 1.1f).coerceIn(0f, 1.5f) / 1.5f
            if (level > beat) beat = level
        }
        beat = (beat - dt * 2.5f).coerceAtLeast(0f)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        program = 0; vao = 0; vbo = 0; texture = 0
    }

    /** The launcher's ð, cropped to the glyph (it sits within 30..78 of the icon's 108). */
    private fun glyph(): Bitmap {
        val px = 512
        val k = px / 48f
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds((-30 * k).toInt(), (-30 * k).toInt(), (78 * k).toInt(), (78 * k).toInt())
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "logo shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "pos")
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private companion object {
        /** Half its height, as a share of the frame's. */
        const val SIZE = 0.24f
        /** How much it brightens the preset beneath (1: doubles it). */
        const val LIFT = 1.1f
        /** Its own gold, always and on top on a beat. */
        const val GLOW = 0.1f
        const val BEAT_GLOW = 0.35f
        const val FADE_S = 0.6f
        /** ~150 Hz at 48 kHz: one-pole low-pass for the bass. */
        const val LOW_PASS = 0.02f
        // The icon's gold, #FFC93C.
        const val GOLD_R = 1f
        const val GOLD_G = 0.788f
        const val GOLD_B = 0.235f

        const val VERTEX = """#version 300 es
layout(location = 0) in vec2 pos;
uniform vec4 rect; // centre x, y; half width, height (clip space)
uniform float rot;
out vec2 uv;
void main() {
    uv = vec2(pos.x, -pos.y) * 0.5 + 0.5;
    vec2 p = pos * rect.zw;
    // Rotate in a square space, so it doesn't shear on a wide frame.
    float a = rect.w / rect.z;
    p.x *= a;
    p = mat2(cos(rot), sin(rot), -sin(rot), cos(rot)) * p;
    p.x /= a;
    gl_Position = vec4(p + rect.xy, 0.0, 1.0);
}"""

        const val FRAGMENT = """#version 300 es
precision mediump float;
in vec2 uv;
uniform sampler2D logo;
uniform vec3 colour;
uniform float opacity;
out vec4 frag;
void main() {
    float a = texture(logo, uv).a;
    frag = vec4(colour * a * opacity, 0.0);
}"""
    }
}
