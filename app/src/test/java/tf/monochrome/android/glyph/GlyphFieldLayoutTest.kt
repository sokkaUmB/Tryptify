// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.glyph.DEFAULT_LANDSCAPE_FIELD
import tf.monochrome.android.ui.glyph.GlyphFieldBounds
import tf.monochrome.android.ui.glyph.MIN_LANDSCAPE_FIELD
import tf.monochrome.android.ui.glyph.glyphLaneSpans

/** The landscape field: a centred column of whole-pixel lanes, and where each is touched. */
class GlyphFieldLayoutTest {

    // A OnePlus 15 held sideways.
    private val width = 2772
    private val height = 1272

    @Test
    fun portraitKeepsTheWholeWidth() {
        val bounds = GlyphFieldBounds.of(1272, 1f)
        assertEquals(318, bounds.laneWidthPx)
        assertEquals(0, bounds.leftPx)
    }

    @Test
    fun landscapeCentresAColumnOfWholePixelLanes() {
        val bounds = GlyphFieldBounds.of(width, DEFAULT_LANDSCAPE_FIELD)
        assertEquals(277, bounds.laneWidthPx)
        assertEquals(832, bounds.leftPx)
        // Centred to the pixel: the gaps either side differ by at most one.
        val right = width - bounds.leftPx - bounds.widthPx
        assertTrue(kotlin.math.abs(right - bounds.leftPx) <= 1)
        // An arrow (82% of its lane) is about a sixth of the height, not half of it.
        val arrow = bounds.laneWidthPx * 0.82f
        assertTrue(arrow / height in 0.15f..0.2f)
    }

    @Test
    fun theFieldNeverGoesNarrowerThanTheMinimum() {
        assertEquals(GlyphFieldBounds.of(width, MIN_LANDSCAPE_FIELD), GlyphFieldBounds.of(width, 0.05f))
    }

    @Test
    fun touchZonesCoverTheScreenAndFollowTheLanes() {
        val bounds = GlyphFieldBounds.of(width, DEFAULT_LANDSCAPE_FIELD)
        val spans = glyphLaneSpans(width, DEFAULT_LANDSCAPE_FIELD, hitboxScale = 1f)
        // Nothing untouchable: edge to edge, no gaps or overlaps.
        assertEquals(0, spans.first().first)
        assertEquals(width - 1, spans.last().last)
        for (i in 1 until spans.size) assertEquals(spans[i - 1].last + 1, spans[i].first)
        // Each lane's zone holds the lane where it is drawn.
        for (i in spans.indices) {
            val centre = bounds.leftPx + bounds.laneWidthPx * i + bounds.laneWidthPx / 2
            assertTrue("lane $i", centre in spans[i])
        }
        // The inner lanes are exactly as wide as drawn; the outer ones reach the edges.
        assertEquals(bounds.laneWidthPx, spans[1].last + 1 - spans[1].first)
        assertEquals(bounds.laneWidthPx, spans[2].last + 1 - spans[2].first)
        assertTrue(spans[0].last + 1 - spans[0].first > bounds.leftPx)
    }

    @Test
    fun aNarrowerHitboxLeavesGuttersBetweenLanesOnly() {
        val spans = glyphLaneSpans(width, DEFAULT_LANDSCAPE_FIELD, hitboxScale = 0.8f)
        // 10% of a 277-pixel lane either side of each boundary between lanes.
        for (i in 1 until spans.size) assertEquals(56, spans[i].first - (spans[i - 1].last + 1))
        // Never at the screen's edges.
        assertEquals(0, spans.first().first)
        assertEquals(width - 1, spans.last().last)
    }
}
