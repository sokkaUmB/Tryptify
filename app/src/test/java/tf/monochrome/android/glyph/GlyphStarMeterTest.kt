// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.glyph.STARS
import tf.monochrome.android.ui.glyph.starSpots

/** The card's meter: twelve stars, the same places for the holes and for their light. */
class GlyphStarMeterTest {

    @Test
    fun `twelve stars across the row, each in its own share, never touching`() {
        val spots = starSpots(Size(600f, 48f), gap = 6f)
        assertEquals(STARS, spots.size)
        val pitch = 600f / STARS
        spots.forEachIndexed { i, spot ->
            assertEquals(pitch * (i + 0.5f), spot.centre.x, 1e-3f)
            assertEquals(24f, spot.centre.y, 1e-3f)
        }
        // Neighbours are at least the gap apart.
        assertTrue(pitch - spots[0].diameter >= 6f - 1e-3f)
    }

    @Test
    fun `a wide row keeps the stars as tall as the row`() {
        val spots = starSpots(Size(2000f, 40f), gap = 6f)
        assertTrue(spots.all { it.diameter == 40f })
    }

    @Test
    fun `nothing to cut before the row is laid out`() {
        assertTrue(starSpots(Size.Zero, gap = 6f).isEmpty())
    }
}
