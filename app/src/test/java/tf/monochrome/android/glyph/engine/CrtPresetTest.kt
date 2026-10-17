// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in CRT looks, the effect's default among them, and a preset's file going out and back in. */
class CrtPresetTest {

    private val height = 2400

    @Test
    fun theDefaultIsTheOwnersTuningAndComesFirst() {
        val default = CrtPresets.DEFAULT
        assertEquals(CrtPresets.STANDARD, CrtPresets.BUILT_IN.first())
        assertEquals(default, CrtPresets.builtIns(height).first())
        assertEquals(GlyphTvShader.CRT, default.shader)
        val tv = default.tuning
        assertEquals(0.3403984f, tv.strength, 1e-6f)
        assertEquals(1f, tv.mask, 1e-6f)
        assertEquals(13.298708f, tv.curveTopDeg, 1e-5f)
        assertEquals(14.036858f, tv.curveSideDeg, 1e-5f)
        assertEquals(0.9638945f, tv.corners, 1e-6f)
        assertEquals(4, tv.pixelSizes[GlyphTvShader.CRT])
        assertEquals(5, tv.pixelSizes[GlyphTvShader.ROYALE])
        // Already in range, and the same on every screen.
        assertEquals(tv, tv.clamped())
        assertEquals(default, CrtPresets.builtIn(CrtPresets.STANDARD, 0))
    }

    @Test
    fun resetGoesBackToTheDefaultGivenAndKeepsTheOtherLooksSizes() {
        val moved = CrtPresets.DEFAULT.tuning.copy(strength = 1f, vignette = 0f)
            .withPixelSize(GlyphTvShader.CRT, 9).withPixelSize(GlyphTvShader.WAVE, 9)
        val back = moved.reset(GlyphTvShader.CRT, CrtPresets.DEFAULT.tuning)
        assertEquals(CrtPresets.DEFAULT.tuning.strength, back.strength)
        assertEquals(CrtPresets.DEFAULT.tuning.vignette, back.vignette)
        assertEquals(4, back.pixelSizes[GlyphTvShader.CRT])
        assertEquals(9, back.pixelSizes[GlyphTvShader.WAVE])
        // Without a base, the shader's own numbers, as before.
        assertEquals(GlyphTvTuning().strength, moved.reset(GlyphTvShader.CRT).strength)
        assertEquals(null, moved.reset(GlyphTvShader.CRT).pixelSizes[GlyphTvShader.CRT])
    }

    @Test
    fun thereAreFiveBuiltInsEachDistinctAndInRange() {
        val presets = CrtPresets.builtIns(height)
        assertEquals(5, presets.size)
        assertEquals(CrtPresets.BUILT_IN, presets.map { it.builtInId })
        // None is the plain picture, and every one is already in range.
        presets.forEach { preset ->
            assertNotEquals(GlyphTvShader.OFF, preset.shader)
            assertEquals(preset.tuning, preset.tuning.clamped())
        }
        // Five different looks, not one look five times.
        assertEquals(5, presets.map { it.shader to it.tuning }.toSet().size)
    }

    @Test
    fun aBuiltInsLinesFollowTheScreen() {
        val arcade = CrtPresets.builtIn(CrtPresets.ARCADE, height)!!
        assertEquals(kotlin.math.round(height / 320f).toInt(), arcade.tuning.pixelSizes[GlyphTvShader.CRT])
        // No height known: the look's own default pixel size.
        assertTrue(CrtPresets.builtIn(CrtPresets.ARCADE, 0)!!.tuning.pixelSizes.isEmpty())
    }

    @Test
    fun aPresetMatchesTheLookItSets() {
        val vhs = CrtPresets.builtIn(CrtPresets.VHS, height)!!
        assertTrue(vhs.matches(vhs.shader, vhs.tuning))
        assertTrue(!vhs.matches(GlyphTvShader.CRT, vhs.tuning))
        assertTrue(!vhs.matches(vhs.shader, vhs.tuning.copy(chromatic = 0f)))
    }

    @Test
    fun anExportedPresetImportsAsTheSameLook() {
        val trinitron = CrtPresets.builtIn(CrtPresets.TRINITRON, height)!!
        val back = CrtPresets.import(CrtPresets.export(trinitron.copy(name = "Desk"))).getOrThrow()
        assertEquals("Desk", back.name)
        assertEquals(trinitron.shader, back.shader)
        assertEquals(trinitron.tuning.clamped(), back.tuning)
        // Imported is always the user's own, whatever it came from.
        assertEquals(null, back.builtInId)
    }

    @Test
    fun anImportIsHeldToEverySlidersRange() {
        val text = """{"type":"tryptify-crt-preset","version":1,"name":"Wild","look":"crt",
            "tuning":"strength=9;brightness=-4;curveTop=500;tiltSide=-999;vignette=3"}"""
        val preset = CrtPresets.import(text).getOrThrow()
        assertEquals(1f, preset.tuning.strength)
        assertEquals(GlyphTvTuning.MIN_BRIGHTNESS, preset.tuning.brightness)
        assertEquals(GlyphTvTuning.MAX_CURVE_DEG, preset.tuning.curveTopDeg)
        assertEquals(-GlyphTvTuning.MAX_TILT_DEG, preset.tuning.tiltSideDeg)
        assertEquals(1f, preset.tuning.vignette)
    }

    @Test
    fun aFileOfAnythingElseIsRefused() {
        assertTrue(CrtPresets.import("""{"type":"something-else","version":1,"look":"crt"}""").isFailure)
        assertTrue(CrtPresets.import("""{"type":"tryptify-crt-preset","version":99,"look":"crt"}""").isFailure)
        assertTrue(CrtPresets.import("""{"type":"tryptify-crt-preset","version":1,"look":"plasma"}""").isFailure)
        assertTrue(CrtPresets.import("not json at all").isFailure)
    }

    @Test
    fun theSavedListRoundTripsAndABrokenOneIsEmpty() {
        val list = CrtPresets.builtIns(height).map { it.copy(name = "Mine " + it.builtInId, builtInId = null) }
        val back = CrtPresets.decodeAll(CrtPresets.encodeAll(list))
        assertEquals(list.map { it.name to it.shader }, back.map { it.name to it.shader })
        assertEquals(list.map { it.tuning.clamped() }, back.map { it.tuning })
        assertEquals(emptyList<CrtPreset>(), CrtPresets.decodeAll("{broken"))
        assertEquals(emptyList<CrtPreset>(), CrtPresets.decodeAll(null))
    }

    @Test
    fun aFileNameIsSafeEverywhere() {
        assertEquals("Living_room.crt.json", CrtPresets.fileName("Living room"))
        assertEquals("crt.crt.json", CrtPresets.fileName("///"))
        assertEquals("Mon_CRT.crt.json", CrtPresets.fileName("Mon CRT!"))
    }
}
