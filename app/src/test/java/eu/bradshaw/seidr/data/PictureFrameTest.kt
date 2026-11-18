package eu.bradshaw.seidr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFrameTest {
    // A landscape photo, 1600x1000, in a 100-unit frame.
    private val start = PictureFrame.start(1600, 1000, 100f)

    @Test fun `starts centred, filling the frame`() {
        assertEquals(0.1f, start.scale, 1e-6f)
        assertTrue(start.isFill)
        assertFalse(start.hasGap)
    }

    @Test fun `fit shows all of it, with a gap above and below`() {
        val fit = start.fit()
        assertEquals(100f / 1600, fit.scale, 1e-6f)
        assertTrue(fit.isFit)
        assertTrue(fit.hasGap)
    }

    @Test fun `a picture that covers the frame can't leave a gap`() {
        // Filling: 160 wide, so 30 to spare each side, none up and down.
        val moved = start.moved(dx = 80f, dy = 20f)
        assertEquals(30f, moved.x, 1e-4f)
        assertEquals(0f, moved.y, 1e-4f)
    }

    @Test fun `a smaller picture stays inside the frame`() {
        // Fitting: 100 x 62.5, so it can slide 18.75 up or down but not sideways.
        val moved = start.fit().moved(dx = 10f, dy = -40f)
        assertEquals(0f, moved.x, 1e-4f)
        assertEquals(-18.75f, moved.y, 1e-4f)
    }

    @Test fun `zoom stays between fitting and four times filling`() {
        assertEquals(start.fitScale, start.moved(0f, 0f, zoom = 0.01f).scale, 1e-6f)
        assertEquals(start.fillScale * 4, start.moved(0f, 0f, zoom = 100f).scale, 1e-6f)
    }

    @Test fun `zooming keeps the point under the fingers still`() {
        val zoomed = start.moved(0f, 0f, zoom = 2f, px = 20f, py = 0f)
        // The picture's point at +20 stays at +20: the centre moves to -20.
        assertEquals(-20f, zoomed.x, 1e-4f)
    }

    @Test fun `letting go near filling or fitting snaps to it, centred`() {
        val nearFill = start.copy(scale = start.fillScale * 1.04f, x = 2f, y = 1f).snapped()
        assertTrue(nearFill.isFill)
        val nearFit = start.copy(scale = start.fitScale * 1.03f, y = 3f).snapped()
        assertTrue(nearFit.isFit)
    }

    @Test fun `letting go elsewhere keeps the framing`() {
        val zoomed = start.moved(dx = 25f, dy = 0f, zoom = 1.5f)
        val let = zoomed.snapped()
        assertEquals(zoomed, let)
        assertFalse(let.isFill)
    }

    @Test fun `a frame scales to the output size with the same placement`() {
        val out = start.moved(dx = 20f, dy = 0f).resized(1200f)
        assertEquals(240f, out.x, 1e-3f)
        assertEquals(1.2f, out.scale, 1e-5f)
    }
}
