// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four built-in CRT looks, and a preset's file going out and back in. */
class CrtPresetTest {

    private val height = 2400

    @Test
    fun thereAreFourBuiltInsEachDistinctAndInRange() {
        val presets = CrtPresets.builtIns(height)
        assertEquals(4, presets.size)
        assertEquals(CrtPresets.BUILT_IN, presets.map { it.builtInId })
        // None is the plain picture, and every one is already in range.
        presets.forEach { preset ->
            assertNotEquals(GlyphTvShader.OFF, preset.shader)
            assertEquals(preset.tuning, preset.tuning.clamped())
        }
        // Four different looks, not one look four times.
        assertEquals(4, presets.map { it.shader to it.tuning }.toSet().size)
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
