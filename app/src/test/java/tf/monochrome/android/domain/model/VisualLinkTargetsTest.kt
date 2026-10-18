package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The visual settings a link can move, and the saved form of the links. */
class VisualLinkTargetsTest {
    @Test
    fun `every target has a unique key`() {
        val keys = VisualLinkTargets.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    // The sides' own bend, so moving the top and bottom does not carry the sides with it.
    private val base = StudioLook(
        lyrics = LyricsFxSettings.DEFAULT.copy(curvatureSideDeg = LyricsFxSettings.DEFAULT.effectiveCurvatureSideDeg),
    )

    // The visualizer's two are stored as whole percents.
    private fun tolerance(target: VisualLinkTarget) = if (target.area == LinkArea.VISUALIZER) 0.5f else 1e-4f

    @Test
    fun `every target reads and writes the same setting`() {
        // Applying a value has to land in the field the target reads, or a
        // link would move one setting while its slider showed another.
        for (target in VisualLinkTargets.ALL) {
            val probe = target.min + (target.max - target.min) * 0.37f
            val moved = VisualLinkTargets.apply(base, mapOf(target.key to probe))
            assertEquals(target.key, probe, target.read(moved), tolerance(target))
        }
    }

    @Test
    fun `a link moves its own setting and nothing else`() {
        for (target in VisualLinkTargets.ALL) {
            val moved = VisualLinkTargets.apply(base, mapOf(target.key to target.min + (target.max - target.min) * 0.37f))
            for (other in VisualLinkTargets.ALL) {
                if (other === target) continue
                assertEquals("${target.key} moved ${other.key}", other.read(base), other.read(moved), 0f)
            }
        }
    }

    @Test
    fun `a value outside the setting's range is held inside it`() {
        for (target in VisualLinkTargets.ALL) {
            val high = VisualLinkTargets.apply(base, mapOf(target.key to target.max * 10f + 10f))
            assertTrue(target.key, target.read(high) <= target.max + 1e-4f)
            val low = VisualLinkTargets.apply(base, mapOf(target.key to target.min - 10f))
            assertTrue(target.key, target.read(low) >= target.min - 1e-4f)
        }
    }

    @Test
    fun `every target is on the tab its key names`() {
        for (target in VisualLinkTargets.ALL) {
            assertTrue(target.key, target.key.startsWith(target.area.prefix + "."))
        }
        // The two glass tabs have the same sliders, each its own.
        assertEquals("player.frost", VisualLinkTargets.glassKey(LinkArea.PLAYER, "frost"))
        assertEquals(LinkArea.PANELS, VisualLinkTargets.byKey("panels.frost")!!.area)
    }

    @Test
    fun `nothing linked leaves the settings as they are`() {
        val lyrics = LyricsFxSettings.DEFAULT
        assertTrue(VisualLinkTargets.applyLyrics(lyrics, emptyMap()) === lyrics)
        val glass = PlayerGlassSettings.DEFAULT
        val tv = tf.monochrome.android.glyph.engine.GlyphTvTuning()
        val ambient = tf.monochrome.android.visualizer.AmbientVisualizerSettings()
        // A renderer is not handed a copy for another part of the app's link.
        val others = mapOf("lyrics.godRayExposure" to 1f, "panels.frost" to 0.5f)
        assertTrue(VisualLinkTargets.applyGlass(glass, LinkArea.PLAYER, others) === glass)
        assertTrue(VisualLinkTargets.applyTv(tv, others) === tv)
        assertTrue(VisualLinkTargets.applyAmbient(ambient, others) === ambient)
        assertEquals(0.5f, VisualLinkTargets.applyGlass(glass, LinkArea.PANELS, others).frost, 0f)
        assertEquals(glass.frost, VisualLinkTargets.applyGlass(glass, LinkArea.PLAYER, others).frost, 0f)
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
        val look = StudioLook()
        val exposure = VisualLinkTargets.byKey("lyrics.godRayExposure")!!
        assertEquals(VisualLinkTarget.Need.GOD_RAYS, exposure.unmet(look.copy(lyrics = look.lyrics.copy(godRays = false))))
        assertEquals(null, exposure.unmet(look.copy(lyrics = look.lyrics.copy(godRays = true))))
        val glow = VisualLinkTargets.byKey("lyrics.glowRadiusDp")!!
        assertEquals(VisualLinkTarget.Need.BASS_REACTION, glow.unmet(look.copy(lyrics = look.lyrics.copy(bassReact = 0f))))
    }

    @Test
    fun `glass, CRT and visualizer targets say what they need`() {
        val look = StudioLook()
        val off = look.player.copy(enabled = false)
        val blur = VisualLinkTargets.byKey("player.hazeBlurDp")!!
        assertEquals(VisualLinkTarget.Need.GLASS_ON, blur.unmet(look.copy(player = off)))
        assertEquals(VisualLinkTarget.Need.BACKDROP, blur.unmet(look.copy(player = look.player.copy(hazeBlurDp = 0f))))
        assertEquals(null, blur.unmet(look))
        // Each glass tab answers for its own switch.
        assertEquals(null, VisualLinkTargets.byKey("panels.hazeBlurDp")!!.unmet(look.copy(player = off)))
        val shadow = VisualLinkTargets.byKey("panels.shadowSoftness")!!
        assertEquals(VisualLinkTarget.Need.GLASS_SHADOW, shadow.unmet(look.copy(panels = look.panels.copy(shadowDepth = 0f))))

        val scanlines = VisualLinkTargets.byKey("tv.scanlines")!!
        assertEquals(VisualLinkTarget.Need.TV, scanlines.unmet(look))
        assertEquals(null, scanlines.unmet(look.copy(tvLook = tf.monochrome.android.glyph.engine.GlyphTvShader.CRT)))
        assertEquals(
            VisualLinkTarget.Need.TV_CRT,
            scanlines.unmet(look.copy(tvLook = tf.monochrome.android.glyph.engine.GlyphTvShader.ROYALE)),
        )
        val bloom = VisualLinkTargets.byKey("tv.royaleBloom")!!
        assertEquals(
            VisualLinkTarget.Need.TV_ROYALE,
            bloom.unmet(look.copy(tvLook = tf.monochrome.android.glyph.engine.GlyphTvShader.CRT)),
        )
        assertEquals(null, VisualLinkTargets.byKey("tv.strength")!!.unmet(look.copy(tvLook = tf.monochrome.android.glyph.engine.GlyphTvShader.ROYALE)))

        val opacity = VisualLinkTargets.byKey("visualizer.opacity")!!
        assertEquals(VisualLinkTarget.Need.AMBIENT, opacity.unmet(look))
        assertEquals(null, opacity.unmet(look.copy(ambient = look.ambient.copy(enabled = true))))
    }

    @Test
    fun `every target has its note in the Studio, and every note a target`() {
        // The Studio's sliders and the CRT's name their links in the source; a
        // key with no target would open nothing, and a target with no slider
        // could only be reached from a theme pack.
        val ui = File("src/main/java/tf/monochrome/android/ui/settings").walk()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val named = Regex("""linkKey = "([a-z]+\.[A-Za-z]+)"""").findAll(ui).map { it.groupValues[1] }.toSet()
        val glass = Regex("""glassKey\(linkArea, "([A-Za-z]+)"\)""").findAll(ui).map { it.groupValues[1] }.toSet()
        val reached = named + glass.flatMap { listOf("player.$it", "panels.$it") }
        for (key in named) assertTrue("$key has no target", VisualLinkTargets.byKey(key) != null)
        for (field in glass) assertTrue("$field has no glass target", VisualLinkTargets.byKey("player.$field") != null)
        for (target in VisualLinkTargets.ALL) assertTrue("${target.key} has no slider", target.key in reached)
    }

    @Test
    fun `linking a moving ray setting keeps the ray clock running`() {
        assertTrue(VisualLinkTargets.movesRayClock(setOf("lyrics.godRaySway")))
        assertFalse(VisualLinkTargets.movesRayClock(setOf("lyrics.godRayExposure")))
        assertFalse(VisualLinkTargets.movesRayClock(emptySet()))
        assertNotEquals(VisualLinkTargets.ALL.size, 0)
    }
}
