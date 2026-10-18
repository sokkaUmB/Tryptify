package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.themestore.previewOf

/** The theme pack as a document: what it holds, what it refuses, and what it never carries. */
class ThemePackTest {
    private val full = ThemePack(
        name = "Night Ferry",
        colours = ThemeColours(theme = "nord", paper = "warm"),
        lyrics = LyricsFxSettings(godRays = true, godRayExposure = 0.9f),
        playerGlass = PlayerGlassSettings(refraction = 0.3f),
        panelGlass = PlayerGlassSettings(bodyOpacity = 0.5f),
        font = "asset:fonts/fraunces.ttf",
        visualizer = ThemeVisualizer(waterfall = SpectrumWaterfallSettings(), ambientOn = true, ambientOpacity = 60),
        maps = ThemeMaps(galaxy = GalaxyVisualSettings()),
        links = listOf(VisualLink("lyrics.godRayExposure", LinkShape(lowest = 0.2f, highest = 1f))),
    )

    @Test
    fun `a pack round-trips through its text form`() {
        val decoded = ThemePackCodec.decode(ThemePackCodec.encode(full))
        assertEquals(full.cleaned(), decoded)
        assertEquals(ThemePart.entries.toSet(), decoded!!.parts)
    }

    @Test
    fun `a whole look is a few kilobytes, far under the cap`() {
        val size = ThemePackCodec.encode(full).toByteArray().size
        assertTrue("a full pack weighed $size bytes", size < 16 * 1024)
    }

    @Test
    fun `only keeps the parts asked for`() {
        val just = full.only(setOf(ThemePart.LYRICS, ThemePart.LINKS))
        assertEquals(setOf(ThemePart.LYRICS, ThemePart.LINKS), just.parts)
        assertNull(just.colours)
        assertNotNull(just.lyrics)
    }

    @Test
    fun `an unknown theme, a see-through colour and a font that is not shipped are all refused`() {
        val pack = ThemePack(
            colours = ThemeColours(theme = "made_up", paper = "glossy", custom = true, accent = 0x00FF0000, background = 0x10000000),
            font = "/storage/emulated/0/fonts/mine.ttf",
        ).cleaned(knownThemes = setOf("monochrome_dark", "nord"), isBundledFont = { it.startsWith("asset:") })
        assertEquals("monochrome_dark", pack.colours!!.theme)
        assertEquals("crisp", pack.colours!!.paper)
        assertEquals(0xFFFF0000.toInt(), pack.colours!!.accent)
        assertEquals(0xFF000000.toInt() or 0x10000000, pack.colours!!.background)
        assertNull(pack.font)
    }

    @Test
    fun `the app's own font travels as an empty id`() {
        val pack = ThemePack(font = "").cleaned(isBundledFont = { false })
        assertEquals("", pack.font)
        assertTrue(ThemePart.FONT in pack.parts)
    }

    @Test
    fun `text that is not a pack is no pack`() {
        assertNull(ThemePackCodec.decode(null))
        assertNull(ThemePackCodec.decode("not json"))
        assertNull(ThemePackCodec.decode("""{"format":"something-else","name":"x"}"""))
        assertNull(ThemePackCodec.decode("""{"format":"tryptheme","name":"${"x".repeat(ThemePackCodec.MAX_BYTES)}"}"""))
    }

    @Test
    fun `a pack from a newer version opens as far as this one understands it`() {
        val text = """{"format":"tryptheme","version":7,"name":"Later","sparkles":{"amount":3},
            "colours":{"theme":"nord","shimmer":true},"links":[{"target":"lyrics.notYet"}]}"""
        val pack = ThemePackCodec.decode(text)!!
        assertEquals("Later", pack.name)
        assertEquals("nord", pack.colours!!.theme)
        assertEquals(emptyList<VisualLink>(), pack.links)
    }

    @Test
    fun `values outside what a slider allows are pulled back in`() {
        val text = """{"format":"tryptheme","lyrics":{"godRayExposure":99,"glassFrost":-5},
            "visualizer":{"ambientOpacity":400,"ambientBlackPoint":90}}"""
        val pack = ThemePackCodec.decode(text)!!
        assertTrue(pack.lyrics!!.godRayExposure <= 2f)
        assertTrue(pack.lyrics!!.glassFrost >= 0f)
        assertEquals(100, pack.visualizer!!.ambientOpacity)
        assertEquals(ThemePack.MAX_AMBIENT_BLACK_POINT, pack.visualizer!!.ambientBlackPoint)
    }

    @Test
    fun `nothing personal is ever published`() {
        val personal = full.copy(
            lyrics = full.lyrics!!.copy(customFont = true, customFontPath = "/data/user/0/fonts/secret.ttf", bluetoothDelayMs = 240f, godRayQuality = 4),
            playerGlass = full.playerGlass!!.copy(tintColor = 0xFF123456.toInt(), sampleRings = 1),
        )
        val shared = ThemePackCodec.forSharing(personal)
        assertFalse(shared.lyrics!!.customFont)
        assertEquals("", shared.lyrics!!.customFontPath)
        assertEquals(LyricsFxSettings.DEFAULT.bluetoothDelayMs, shared.lyrics!!.bluetoothDelayMs, 0f)
        assertEquals(LyricsFxSettings.DEFAULT.godRayQuality, shared.lyrics!!.godRayQuality)
        assertEquals(PlayerGlassSettings.DEFAULT.tintColor, shared.playerGlass!!.tintColor)
        // The look itself goes up untouched.
        assertEquals(0.9f, shared.lyrics!!.godRayExposure, 0f)
        assertFalse(ThemePackCodec.encode(shared).contains("secret"))
    }

    @Test
    fun `the preview is the pack over the viewer's own look, with their personal settings`() {
        val own = ThemePack(
            colours = ThemeColours(theme = "gold"),
            lyrics = LyricsFxSettings(bluetoothDelayMs = 180f),
            font = "",
        )
        val pack = ThemePack(lyrics = LyricsFxSettings(godRayExposure = 1.2f))
        val preview = previewOf(own, pack)
        assertEquals("gold", preview.colours!!.theme)
        assertEquals(1.2f, preview.lyrics!!.godRayExposure, 0f)
        assertEquals(180f, preview.lyrics!!.bluetoothDelayMs, 0f)
        assertEquals("", preview.font)
    }
}
