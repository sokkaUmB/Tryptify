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
        assertEquals(0f, defaults.curveTopDeg)
        assertEquals(0f, defaults.curveSideDeg)
        assertFalse(defaults.hasTube)
        assertFalse(defaults.curved)
        assertTrue(defaults.copy(chromatic = 0.2f).hasTube)
        assertFalse(defaults.copy(chromatic = 0.2f).curved)
        assertTrue(defaults.copy(curveTopDeg = 5f).curved)
        assertTrue(defaults.copy(curveSideDeg = 5f).curved)
    }

    @Test
    fun theClassicTubeIsAboutFourteenAndSixteenDegrees() {
        // The original's warp, 1/32 across and 1/24 down, as two angles.
        assertEquals(1f / 32f, defaults.copy(curveSideDeg = 14.14f).warpX, 1e-4f)
        assertEquals(1f / 24f, defaults.copy(curveTopDeg = 16.26f).warpY, 1e-4f)
        // Each angle bows its own edges only.
        assertEquals(0f, defaults.copy(curveSideDeg = 20f).warpY)
        assertEquals(0f, defaults.copy(curveTopDeg = 20f).warpX)
        assertEquals(20f, GlyphTvTuning.degreesOf(GlyphTvTuning.warpOf(20f)), 1e-3f)
        // Three dp apart at most: ten and a half pixels at density 3.5.
        assertEquals(10.5f, defaults.copy(chromatic = 1f).chromaticPx(3.5f), 1e-4f)
    }

    @Test
    fun aTapOnTheBowedTubeGoesWhereItIsSeen() {
        val out = FloatArray(2)
        val bowed = defaults.copy(curveTopDeg = 30f, curveSideDeg = 25f)
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
    fun theSlidersSurviveTheStore() {
        val tuned = GlyphTvTuning(
            strength = 0.7f,
            brightness = 1.3f,
            scanlines = 0.9f,
            mask = 0.2f,
            glow = 0.6f,
            sharpness = 0.1f,
            chromatic = 0.35f,
            curveTopDeg = 16.3f,
            curveSideDeg = 14.1f,
            royaleMask = GlyphTvTuning.ROYALE_SHADOW,
            royaleTriad = 11,
            royaleMaskStrength = 0.6f,
            royaleBeamWidth = 0.8f,
            royaleBeamShape = 0.5f,
            royaleHalation = 0.2f,
            royaleDiffusion = 0.3f,
            royaleBloom = 0.4f,
            royaleBorder = 0.7f,
        ).withPixelSize(GlyphTvShader.CRT, 3).withPixelSize(GlyphTvShader.ROYALE, 7)
        assertEquals(tuned, GlyphTvTuning.decode(tuned.encode()))
        assertEquals(defaults, GlyphTvTuning.decode(null))
        assertEquals(defaults, GlyphTvTuning.decode(""))
        // Out of range, unreadable or unknown: held, or left at its default.
        val odd = GlyphTvTuning.decode(
            "strength=9;brightness=NaN;mask=x;curveTop=-2;curveSide=90;royaleMask=7;royaleTriad=99;px.crt=99;px.nope=3;what=1",
        )
        assertEquals(1f, odd.strength)
        assertEquals(1f, odd.brightness)
        assertEquals(GlyphTvTuning.DEFAULT_MASK, odd.mask)
        assertEquals(0f, odd.curveTopDeg)
        assertEquals(GlyphTvTuning.MAX_CURVE_DEG, odd.curveSideDeg)
        assertEquals(GlyphTvTuning.ROYALE_SHADOW, odd.royaleMask)
        assertEquals(24, odd.royaleTriadPx(3.5f))
        assertEquals(8, odd.pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
    }

    @Test
    fun theOneCurvatureSliderBecomesTheSameTwoAngles() {
        // A quarter of the old slider was the original's 1/32 and 1/24.
        val old = GlyphTvTuning.decode("strength=1.0;curvature=0.25")
        assertEquals(1f / 32f, old.warpX, 1e-5f)
        assertEquals(1f / 24f, old.warpY, 1e-5f)
        // A line that has the angles keeps them.
        assertEquals(3f, GlyphTvTuning.decode("curvature=0.25;curveTop=3.0").curveTopDeg)
    }

    @Test
    fun royaleStartsAsTheOriginal() {
        assertEquals(GlyphTvTuning.ROYALE_SLOT, defaults.royaleMask)
        assertEquals(1f, defaults.royaleMaskStrength)
        assertEquals(0.3f, defaults.royaleMaxSigma, 1e-6f)
        assertEquals(4f, defaults.royaleMaxShape, 1e-6f)
        assertEquals(0f, defaults.royaleHalationWeight)
        assertEquals(0.075f, defaults.royaleDiffusionWeight, 1e-6f)
        assertEquals(0f, defaults.royaleBloom)
        assertEquals(0.015f, defaults.royaleBorderSize, 1e-6f)
        // Its lines start at the original's 480 down the screen; its triads at 2.5 dp.
        assertEquals(6, defaults.pixelSize(GlyphTvShader.ROYALE, 3.5f, 2772))
        assertEquals(9, defaults.royaleTriadPx(3.5f))
        // Two pixels a line at least, so a beam has room to widen.
        assertEquals(2, defaults.withPixelSize(GlyphTvShader.ROYALE, 1).pixelSize(GlyphTvShader.ROYALE, 3.5f, 2772))
    }

    @Test
    fun resetPutsBackOneLookAndKeepsTheOthersSizes() {
        val tuned = defaults.copy(royaleBloom = 0.9f, curveTopDeg = 20f, strength = 0.3f)
            .withPixelSize(GlyphTvShader.ROYALE, 9)
            .withPixelSize(GlyphTvShader.CRT, 4)
        val reset = tuned.reset(GlyphTvShader.ROYALE)
        assertEquals(0f, reset.royaleBloom)
        assertEquals(0f, reset.curveTopDeg)
        assertEquals(1f, reset.strength)
        assertEquals(mapOf(GlyphTvShader.CRT to 4), reset.pixelSizes)
    }

    @Test
    fun aFlatSquareTubeLeavesEveryPointWhereItIs() {
        val view = defaults.tubeView(1272f, 2772f)
        assertEquals(1f, view.fitX, 1e-6f)
        assertEquals(1f, view.fitY, 1e-6f)
        assertEquals(0f, view.shiftX, 1e-6f)
        val p = view.plane(0.3f, -0.6f)!!
        assertEquals(0.3f, p.first, 1e-5f)
        assertEquals(-0.6f, p.second, 1e-5f)
        assertFalse(defaults.reshaped)
        assertFalse(defaults.hasTube)
    }

    @Test
    fun aTiltedTubeIsCentredAndFillsTheScreen() {
        val tilted = defaults.copy(tiltSideDeg = 20f)
        assertTrue(tilted.reshaped)
        val view = tilted.tubeView(1272f, 2772f)
        // Every corner of the turned picture lands on the screen's edge or
        // inside it, and the widest reaches it: fitted, and centred.
        var maxX = 0f
        var maxY = 0f
        for (u in floatArrayOf(-1f, 1f)) for (v in floatArrayOf(-1f, 1f)) {
            // Forward: the screen point whose line of sight meets (u, v).
            var lo = -3f
            var hi = 3f
            repeat(60) {
                val mid = (lo + hi) / 2f
                val back = view.plane(mid, 0f)!!.first
                if (back < u) lo = mid else hi = mid
            }
            maxX = maxOf(maxX, kotlin.math.abs((lo + hi) / 2f))
            maxY = maxOf(maxY, 1f)
        }
        assertEquals(1f, maxX, 2e-3f)
        // The nearer side stands taller: the far edge's picture spans less.
        val nearTop = view.plane(-0.95f, -0.99f)
        val farTop = view.plane(0.95f, -0.99f)
        assertTrue(nearTop != null && farTop != null)
        assertTrue(kotlin.math.abs(nearTop!!.second) != kotlin.math.abs(farTop!!.second))
    }

    @Test
    fun aFramedTubeDrawsThePictureSmaller() {
        val framed = defaults.copy(bezel = 1f)
        val view = framed.tubeView(1000f, 2000f)
        assertEquals(1f + GlyphTvTuning.BEZEL_MOST, view.fitX, 1e-5f)
        // A tap in the frame is held to the picture's edge.
        val out = FloatArray(2)
        framed.tubeSource(2f, 1000f, 1000f, 2000f, out)
        assertEquals(0f, out[0], 0f)
        // The middle still taps the middle.
        framed.tubeSource(500f, 1000f, 1000f, 2000f, out)
        assertEquals(500f, out[0], 1e-3f)
        assertEquals(1000f, out[1], 1e-3f)
    }

    @Test
    fun theOldTvLookIsInRangeAndSurvivesTheStore() {
        val old = GlyphTvTuning.oldTv(2772, keep = defaults.withPixelSize(GlyphTvShader.CRT, 3))
        assertEquals(old, old.clamped())
        assertEquals(old, GlyphTvTuning.decode(old.encode()))
        assertEquals(12, old.pixelSize(GlyphTvShader.ROYALE, 3.5f, 2772))
        assertEquals(3, old.pixelSize(GlyphTvShader.CRT, 3.5f, 2772))
        assertTrue(old.tilted && old.curved && old.hasTube)
        // Tilts are held to their range, either way.
        assertEquals(-GlyphTvTuning.MAX_TILT_DEG, defaults.copy(tiltSideDeg = -99f).clamped().tiltSideDeg)
    }
}
