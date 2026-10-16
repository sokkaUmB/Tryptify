// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.glyph.LIP_UNITS
import tf.monochrome.android.ui.glyph.PAD_UNITS
import tf.monochrome.android.ui.glyph.StageGeometry
import tf.monochrome.android.ui.glyph.WOOFER_PUMP
import tf.monochrome.android.ui.glyph.WOOFER_PUMP_SHARE
import tf.monochrome.android.ui.glyph.wooferPump

/** The stage behind the play: the tilted pad seen from its camera, and the woofers on the beat. */
class GlyphDanceStageTest {

    private val phone = StageGeometry(Size(1080f, 2400f))

    @Test
    fun `the pad is a floor seen from above, its near edge wider and lower than its far one`() {
        val farLeft = phone.project(-PAD_UNITS / 2f, 0f)
        val farRight = phone.project(PAD_UNITS / 2f, 0f)
        val nearLeft = phone.project(-PAD_UNITS / 2f, PAD_UNITS)
        val nearRight = phone.project(PAD_UNITS / 2f, PAD_UNITS)

        // The hinge is on the screen as drawn: no depth, no scaling.
        assertEquals(phone.padTop, farLeft.y, 0.01f)
        assertEquals(PAD_UNITS * phone.unit, farRight.x - farLeft.x, 0.01f)
        // Nearer is bigger, and below.
        assertTrue(nearRight.x - nearLeft.x > farRight.x - farLeft.x)
        assertTrue(nearLeft.y > farLeft.y)
        assertEquals(nearLeft.y, nearRight.y, 0.01f)
        // Square to the screen: the camera is straight in front of the pad.
        assertEquals(phone.centreX, (nearLeft.x + nearRight.x) / 2f, 0.01f)
    }

    @Test
    fun `the pad's front lip shows under its near edge`() {
        val edge = phone.project(0f, PAD_UNITS)
        val lipBottom = phone.project(0f, PAD_UNITS, -LIP_UNITS)
        assertTrue(lipBottom.y > edge.y)
        // A lip, not a wall: less than a fifth of the pad's depth on screen.
        val depth = edge.y - phone.project(0f, 0f).y
        assertTrue(lipBottom.y - edge.y < depth / 5f)
    }

    @Test
    fun `a wide screen keeps the pad its own shape`() {
        val wide = StageGeometry(Size(2400f, 1080f))
        val farLeft = wide.project(-PAD_UNITS / 2f, 0f)
        val farRight = wide.project(PAD_UNITS / 2f, 0f)
        // Scaled by the height, so it fits, and centred.
        assertEquals(1080f / 816f, wide.unit, 1e-4f)
        assertEquals(1200f, (farLeft.x + farRight.x) / 2f, 0.01f)
    }

    @Test
    fun `the woofers pump on each beat and are at rest between`() {
        assertEquals(WOOFER_PUMP, wooferPump(0f), 1e-6f)
        assertEquals(WOOFER_PUMP, wooferPump(7f), 1e-6f)
        assertEquals(1f, wooferPump(WOOFER_PUMP_SHARE), 1e-6f)
        assertEquals(1f, wooferPump(3.6f), 1e-6f)
        // Out on the beat, then back, never past rest.
        var last = wooferPump(2f)
        var beat = 2f
        while (beat < 2f + WOOFER_PUMP_SHARE) {
            beat += 0.01f
            val now = wooferPump(beat)
            assertTrue(now <= last && now >= 1f)
            last = now
        }
    }

    @Test
    fun `the woofers wait for the first beat`() {
        assertEquals(1f, wooferPump(-0.5f), 0f)
        assertEquals(1f, wooferPump(Float.NaN), 0f)
    }
}
