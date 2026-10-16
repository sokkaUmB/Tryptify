// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning

/** The TV shader's sliders: whole-pixel sizes, and defaults that are the original's look. */
class GlyphTvTuningTest {

    private val defaults = GlyphTvTuning()

    @Test
    fun theCrtDefaultsAreTheOriginalsConstants() {
        assertEquals(-8f, defaults.crtScan, 0.0001f)
        assertEquals(-3f, defaults.crtSharpness, 0.0001f)
        assertEquals(0.5f, defaults.crtMaskDark, 0.0001f)
        assertEquals(1.5f, defaults.crtMaskLight, 0.0001f)
        assertEquals(0.15f, defaults.crtBloom, 0.0001f)
        assertEquals(1f, defaults.strength)
        assertEquals(1f, defaults.brightness)
    }

    @Test
    fun theCrtStartsAtThePhonesOwnPixels() {
        // OnePlus 15: 1272 x 2772 at density 3.5.
        assertEquals(1, defaults.pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
    }

    @Test
    fun theMasksStartAtAMonitorsPixelAndWaveAtTheOriginalsLines() {
        assertEquals(6, defaults.pixelSize(GlyphTvShader.SCANLINES, 3.5f, 2772))
        assertEquals(4, defaults.pixelSize(GlyphTvShader.DIAGONAL, 2.625f, 2400))
        assertEquals(6, defaults.pixelSize(GlyphTvShader.WAVE, 3.5f, 2772))
        assertEquals(5, defaults.pixelSize(GlyphTvShader.WAVE, 2.625f, 2400))
        // A window not measured yet still gives a usable line.
        assertEquals(6, defaults.pixelSize(GlyphTvShader.WAVE, 3.5f, 0))
    }

    @Test
    fun aSizeIsKeptPerLookAndHeldToItsRange() {
        val tuned = defaults.withPixelSize(GlyphTvShader.CRT, 4).withPixelSize(GlyphTvShader.WAVE, 1)
        assertEquals(4, tuned.pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
        // Wave's line needs three rows for its bright middle and dim edges.
        assertEquals(3, tuned.pixelSize(GlyphTvShader.WAVE, 3.5f, 2772))
        assertEquals(6, tuned.pixelSize(GlyphTvShader.SCANLINES, 3.5f, 2772))
        assertEquals(8, defaults.withPixelSize(GlyphTvShader.CRT, 40).pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
    }

    @Test
    fun theSlidersEndsAreSoftAndHard() {
        assertEquals(-2f, defaults.copy(scanlines = 0f).crtScan, 0.0001f)
        assertEquals(-16f, defaults.copy(scanlines = 1f).crtScan, 0.0001f)
        assertEquals(1f, defaults.copy(mask = 0f).crtMaskDark, 0.0001f)
        assertEquals(1f, defaults.copy(mask = 0f).crtMaskLight, 0.0001f)
        assertEquals(0f, defaults.copy(glow = 0f).crtBloom, 0.0001f)
    }

    @Test
    fun theTubeStartsFlatAndInLine() {
        assertEquals(0f, defaults.chromatic)
        assertEquals(0f, defaults.curvature)
        assertFalse(defaults.hasTube)
        assertFalse(defaults.curved)
        assertTrue(defaults.copy(chromatic = 0.2f).hasTube)
        assertFalse(defaults.copy(chromatic = 0.2f).curved)
        assertTrue(defaults.copy(curvature = 0.2f).curved)
    }

    @Test
    fun aQuarterOfCurvatureIsTheClassicTube() {
        val quarter = defaults.copy(curvature = 0.25f)
        assertEquals(1f / 32f, quarter.warpX, 1e-6f)
        assertEquals(1f / 24f, quarter.warpY, 1e-6f)
        // Three dp apart at most: ten and a half pixels at density 3.5.
        assertEquals(10.5f, defaults.copy(chromatic = 1f).chromaticPx(3.5f), 1e-4f)
    }

    @Test
    fun aTapOnTheBowedTubeGoesWhereItIsSeen() {
        val out = FloatArray(2)
        val bowed = defaults.copy(curvature = 1f)
        // The middle, and the middle of each side, stay where they are.
        bowed.tubeSource(500f, 1000f, 1000f, 2000f, out)
        assertEquals(500f, out[0], 1e-3f)
        assertEquals(1000f, out[1], 1e-3f)
        bowed.tubeSource(1000f, 1000f, 1000f, 2000f, out)
        assertEquals(1000f, out[0], 1e-3f)
        // Toward a corner the picture is drawn in, so a tap there reaches
        // further out: the shader's warp, by hand.
        bowed.tubeSource(750f, 500f, 1000f, 2000f, out)
        assertEquals((0.5f * (1f + 0.25f * bowed.warpX) * 0.5f + 0.5f) * 1000f, out[0], 1e-3f)
        assertEquals((-0.5f * (1f + 0.25f * bowed.warpY) * 0.5f + 0.5f) * 2000f, out[1], 1e-3f)
        // Past the tube's edge, the dark corner, a tap is held to the picture.
        bowed.tubeSource(0f, 0f, 1000f, 2000f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        // Flat, every tap stays put.
        defaults.tubeSource(123f, 456f, 1000f, 2000f, out)
        assertEquals(123f, out[0], 1e-4f)
        assertEquals(456f, out[1], 1e-4f)
    }

    @Test
    fun theAppWideSlidersSurviveTheStore() {
        val tuned = GlyphTvTuning(
            strength = 0.7f,
            brightness = 1.3f,
            scanlines = 0.9f,
            mask = 0.2f,
            glow = 0.6f,
            sharpness = 0.1f,
            chromatic = 0.35f,
            curvature = 0.25f,
        ).withPixelSize(GlyphTvShader.CRT, 3).withPixelSize(GlyphTvShader.WAVE, 7)
        assertEquals(tuned, GlyphTvTuning.decode(tuned.encode()))
        assertEquals(defaults, GlyphTvTuning.decode(null))
        assertEquals(defaults, GlyphTvTuning.decode(""))
        // Out of range, unreadable or unknown: held, or left at its default.
        val odd = GlyphTvTuning.decode("strength=9;brightness=NaN;mask=x;curvature=-2;px.crt=99;px.nope=3;what=1")
        assertEquals(1f, odd.strength)
        assertEquals(1f, odd.brightness)
        assertEquals(GlyphTvTuning.DEFAULT_MASK, odd.mask)
        assertEquals(0f, odd.curvature)
        assertEquals(8, odd.pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
    }
}
