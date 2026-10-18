package tf.monochrome.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Variations of one font, found among imported files: the app's own Inter is
 * five static files, one per weight, which is exactly how most fonts arrive.
 */
class FontFamiliesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val inter = listOf(
        "inter_light" to 300,
        "inter_regular" to 400,
        "inter_medium" to 500,
        "inter_semibold" to 600,
        "inter_bold" to 700,
    )

    private fun res(name: String) = File("src/main/res/font/$name.ttf")

    /** A library folder holding [names] (from res/font) and [assets] (from assets/fonts). */
    private fun library(names: List<String>, assets: List<String> = emptyList()): File {
        val dir = temp.newFolder()
        names.forEach { res(it).copyTo(File(dir, "$it.ttf")) }
        assets.forEach { File("src/main/assets/fonts/$it").copyTo(File(dir, it)) }
        return dir
    }

    @Test
    fun `the inspector reads each cut's weight and family`() {
        for ((name, weight) in inter) {
            val font = FontFileInspector.inspect(res(name)) as FontInspection.Font
            assertEquals(name, weight, font.weight)
            assertFalse(name, font.italic)
            assertFalse(name, font.variable)
            assertEquals(name, "inter", FontFamilies.key(font.groupFamily!!))
        }
    }

    @Test
    fun `five files of one font are one family`() {
        val dir = library(inter.map { it.first })
        val families = FontFamilies.scan(dir)
        assertEquals(1, families.size)
        val family = families.single()
        assertTrue(family.hasVariations)
        assertEquals(listOf(300, 400, 500, 600, 700), family.cuts.map { it.weight })
        // Regular stands for it, whichever file was picked.
        assertEquals("inter_regular.ttf", family.representative.file.name)
        val fromBold = FontFamilies.familyOf(File(dir, "inter_bold.ttf"))!!
        assertEquals(5, fromBold.cuts.size)
    }

    @Test
    fun `another font in the library stays its own`() {
        val dir = library(listOf("inter_regular", "inter_bold"), assets = listOf("sora.ttf"))
        val families = FontFamilies.scan(dir)
        assertEquals(2, families.size)
        val sora = FontFamilies.familyOf(File(dir, "sora.ttf"))!!
        assertEquals(1, sora.cuts.size)
        assertFalse(sora.hasVariations)
        assertTrue(sora.cuts.single().variable)
    }

    @Test
    fun `a lone file is a family of one`() {
        val dir = library(listOf("inter_medium"))
        val family = FontFamilies.familyOf(File(dir, "inter_medium.ttf"))!!
        assertEquals(1, family.cuts.size)
        assertFalse(family.hasVariations)
    }

    @Test
    fun `a file that is not a font joins no family`() {
        val dir = library(listOf("inter_regular"))
        File(dir, "notes.ttf").writeText("not a font")
        assertEquals(1, FontFamilies.scan(dir).single().cuts.size)
        assertEquals(null, FontFamilies.familyOf(File(dir, "notes.ttf")))
    }

    @Test
    fun `the representative is upright and nearest Regular`() {
        fun cut(weight: Int, italic: Boolean = false, variable: Boolean = false) =
            FontCut(File("$weight$italic$variable.ttf"), "X", weight, italic, variable)
        assertEquals(400, ImportedFamily("X", listOf(cut(700), cut(400, italic = true), cut(400))).representative.weight)
        assertFalse(ImportedFamily("X", listOf(cut(700), cut(400, italic = true), cut(400))).representative.italic)
        // Medium before Light when both are a step from Regular.
        assertEquals(500, ImportedFamily("X", listOf(cut(300), cut(500))).representative.weight)
        // A variable file draws every weight, so it stands for the family.
        assertTrue(ImportedFamily("X", listOf(cut(400), cut(400, variable = true))).representative.variable)
    }

    @Test
    fun `a width axis is read where a font has one`() {
        // Plex Sans draws 75% to 100% wide itself; Chivo has weight alone.
        val plex = FontFileInspector.inspect(File("src/main/assets/fonts/ibm_plex_sans.ttf")) as FontInspection.Font
        assertEquals(75f, plex.widthAxis!!.start, 0f)
        assertEquals(100f, plex.widthAxis!!.endInclusive, 0f)
        assertTrue("wght" in plex.axes)
        val chivo = FontFileInspector.inspect(File("src/main/assets/fonts/chivo.ttf")) as FontInspection.Font
        assertEquals(null, chivo.widthAxis)
        // A static font has no axes at all.
        assertTrue((FontFileInspector.inspect(res("inter_regular")) as FontInspection.Font).axes.isEmpty())
    }

    @Test
    fun `an old font's family loses its weight word`() {
        assertEquals("Inter", FontFileInspector.withoutStyleWords("Inter Light"))
        assertEquals("Source Sans Pro", FontFileInspector.withoutStyleWords("Source Sans Pro Bold Italic"))
        assertEquals("Fira Sans", FontFileInspector.withoutStyleWords("Fira Sans Semi-Bold"))
        // Never the whole name: a family called "Black" is still "Black".
        assertEquals("Black", FontFileInspector.withoutStyleWords("Black"))
        assertEquals("inter", FontFamilies.key(" Inter "))
        assertEquals(FontFamilies.key("Fira-Sans"), FontFamilies.key("fira sans"))
    }
}
