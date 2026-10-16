// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
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
}
