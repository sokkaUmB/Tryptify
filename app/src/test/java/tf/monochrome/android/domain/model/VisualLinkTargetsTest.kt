package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The visual settings a link can move, and the saved form of the links. */
class VisualLinkTargetsTest {
    @Test
    fun `every target has a unique key`() {
        val keys = VisualLinkTargets.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every target reads and writes the same setting`() {
        // Applying a value has to land in the field the target reads, or a
        // link would move one setting while its slider showed another.
        for (target in VisualLinkTargets.ALL) {
            val probe = target.min + (target.max - target.min) * 0.37f
            val moved = VisualLinkTargets.applyLyrics(LyricsFxSettings.DEFAULT, mapOf(target.key to probe))
            assertEquals(target.key, probe, target.read(moved), 1e-4f)
        }
    }

    @Test
    fun `a value outside the setting's range is held inside it`() {
        for (target in VisualLinkTargets.ALL) {
            val moved = VisualLinkTargets.applyLyrics(LyricsFxSettings.DEFAULT, mapOf(target.key to target.max * 10f + 10f))
            assertTrue(target.key, target.read(moved) <= target.max + 1e-4f)
        }
    }

    @Test
    fun `nothing linked leaves the settings as they are`() {
        val base = LyricsFxSettings.DEFAULT
        assertTrue(VisualLinkTargets.applyLyrics(base, emptyMap()) === base)
    }

    @Test
    fun `the saved form round-trips`() {
        val links = listOf(
            VisualLink("lyrics.godRayExposure", LinkShape(lowest = 0.2f, highest = 1.1f, way = LinkWay.BOTH, formula = "value + band * 0.5")),
            VisualLink("lyrics.glowRadiusDp", LinkShape(band = LinkBand.CUSTOM, loHz = 200f, hiHz = 900f, lowest = 10f, highest = 80f), paused = true),
        )
        assertEquals(links, VisualLinkCodec.decode(VisualLinkCodec.encode(links)))
    }

    @Test
    fun `a file from a newer version still opens`() {
        val text = """
            [
              {"target":"lyrics.godRayExposure","shape":{"band":"SUB_BASS","lowest":0.1,"highest":0.9,"wobble":3},"colour":"red"},
              {"target":"lyrics.somethingNew","shape":{}},
              {"target":"lyrics.godRayExposure","shape":{"lowest":0.5}},
              {"target":"lyrics.glassFrost","shape":{"lowest":-4,"highest":12}}
            ]
        """.trimIndent()
        val links = VisualLinkCodec.decode(text)
        assertEquals(listOf("lyrics.godRayExposure", "lyrics.glassFrost"), links.map { it.target })
        // An enum value this version does not know falls back to the default.
        assertEquals(LinkBand.KICK, links[0].shape.band)
        assertEquals(0.1f, links[0].shape.lowest, 1e-6f)
        // Clamped into the setting's own range.
        assertEquals(0f, links[1].shape.lowest, 0f)
        assertEquals(1f, links[1].shape.highest, 0f)
    }

    @Test
    fun `rubbish is no links rather than a crash`() {
        assertTrue(VisualLinkCodec.decode("not json").isEmpty())
        assertTrue(VisualLinkCodec.decode(null).isEmpty())
        assertTrue(VisualLinkCodec.decode("{}").isEmpty())
    }

    @Test
    fun `a target says what it needs to be seen`() {
        val exposure = VisualLinkTargets.byKey("lyrics.godRayExposure")!!
        assertEquals(VisualLinkTarget.Need.GOD_RAYS, exposure.unmet(LyricsFxSettings.DEFAULT.copy(godRays = false)))
        assertEquals(null, exposure.unmet(LyricsFxSettings.DEFAULT.copy(godRays = true)))
        val glow = VisualLinkTargets.byKey("lyrics.glowRadiusDp")!!
        assertEquals(VisualLinkTarget.Need.BASS_REACTION, glow.unmet(LyricsFxSettings.DEFAULT.copy(bassReact = 0f)))
    }

    @Test
    fun `linking a moving ray setting keeps the ray clock running`() {
        assertTrue(VisualLinkTargets.movesRayClock(setOf("lyrics.godRaySway")))
        assertFalse(VisualLinkTargets.movesRayClock(setOf("lyrics.godRayExposure")))
        assertFalse(VisualLinkTargets.movesRayClock(emptySet()))
        assertNotEquals(VisualLinkTargets.ALL.size, 0)
    }
}
