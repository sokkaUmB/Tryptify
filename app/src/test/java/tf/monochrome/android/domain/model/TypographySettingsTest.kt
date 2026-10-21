package tf.monochrome.android.domain.model

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.theme.WidthFont
import tf.monochrome.android.ui.theme.buildTypography

/** The Fonts page's typography table, and the type scale it sits over. */
class TypographySettingsTest {

    @Test
    fun `a title is two weights above its description`() {
        val type = buildTypography(FontFamily.Default, 1f)
        // Row titles and titles SemiBold, descriptions Regular.
        for (title in listOf(type.bodyLarge, type.titleLarge, type.titleMedium, type.titleSmall)) {
            assertEquals(FontWeight.SemiBold, title.fontWeight)
        }
        assertEquals(FontWeight.Normal, type.bodyMedium.fontWeight)
        assertEquals(FontWeight.Normal, type.bodySmall.fontWeight)
    }

    @Test
    fun `untouched, every style is the app's own`() {
        for (role in TypeRole.entries) assertEquals(role.name, TypeDefaults.of(role), TypographySettings.DEFAULT.resolved(role))
    }

    @Test
    fun `a change lands in its style and no other`() {
        val table = TypographySettings.DEFAULT
            .with(TypeRole.BODY_MEDIUM, TypeStyleOverride(weight = 300, sizeSp = 16f, letterSpacingSp = 0.1f))
        val type = buildTypography(FontFamily.Default, 1f, table)
        assertEquals(FontWeight.Light, type.bodyMedium.fontWeight)
        assertEquals(16f, type.bodyMedium.fontSize.value, 0f)
        assertEquals(0.1f, type.bodyMedium.letterSpacing.value, 1e-6f)
        // A bigger size takes a taller line in proportion: 20 at 14sp, so 16sp gets 22.86.
        assertEquals(20f * 16f / 14f, type.bodyMedium.lineHeight.value, 1e-3f)
        // Its neighbours are as they were.
        assertEquals(FontWeight.Normal, type.bodySmall.fontWeight)
        assertEquals(15f, type.bodyLarge.fontSize.value, 0f)
    }

    @Test
    fun `the text-size setting scales the table's sizes too`() {
        val table = TypographySettings.DEFAULT.with(TypeRole.TITLE_LARGE, TypeStyleOverride(sizeSp = 20f))
        assertEquals(30f, buildTypography(FontFamily.Default, 1.5f, table).titleLarge.fontSize.value, 1e-4f)
    }

    @Test
    fun `a style's own font is used, and the main font everywhere else`() {
        val main = FontFamily.Serif
        val other = FontFamily.Monospace
        val table = TypographySettings.DEFAULT.with(TypeRole.LABEL_LARGE, TypeStyleOverride(fontId = "asset:fonts/x.ttf"))
        val type = buildTypography(main, 1f, table) { id, _ -> if (id == "asset:fonts/x.ttf") WidthFont(other, 1f) else null }
        assertSame(other, type.labelLarge.fontFamily)
        assertSame(main, type.labelMedium.fontFamily)
        assertSame(main, type.bodyLarge.fontFamily)
        // A font that cannot be loaded falls back to the main one.
        assertSame(main, buildTypography(main, 1f, table) { _, _ -> null }.labelLarge.fontFamily)
        assertEquals(setOf("asset:fonts/x.ttf"), table.fontIds)
    }

    @Test
    fun `the page's width reaches every style a style's own width overrides`() {
        val table = TypographySettings.DEFAULT.withWidth(85)
            .with(TypeRole.TITLE_LARGE, TypeStyleOverride(widthPercent = 110))
        assertEquals(85, table.resolved(TypeRole.BODY_LARGE).widthPercent)
        assertEquals(110, table.resolved(TypeRole.TITLE_LARGE).widthPercent)
        // A font with no width axis is squeezed to the width; one with an axis is drawn at it.
        val squeezed = buildTypography(FontFamily.Default, 1f, table)
        assertEquals(0.85f, squeezed.bodyLarge.textGeometricTransform!!.scaleX, 1e-6f)
        val drawn = buildTypography(FontFamily.Default, 1f, table) { _, w -> WidthFont(FontFamily.SansSerif, 1f).takeIf { w == 85 } }
        assertSame(FontFamily.SansSerif, drawn.bodyLarge.fontFamily)
        assertEquals(null, drawn.bodyLarge.textGeometricTransform)
        // At 100% nothing is transformed.
        assertEquals(null, buildTypography(FontFamily.Default, 1f).bodyLarge.textGeometricTransform)
        assertEquals(setOf<Pair<String?, Int>>(null to 85, null to 110), table.fontWidths)
    }

    @Test
    fun `widths are held to the page's steps and range`() {
        assertEquals(85, TypeStyleOverride.clampWidth(84))
        assertEquals(TypeStyleOverride.MIN_WIDTH, TypeStyleOverride.clampWidth(10))
        assertEquals(TypeStyleOverride.MAX_WIDTH, TypeStyleOverride.clampWidth(400))
        assertEquals(90, TypeStyleOverride(widthPercent = 91).clamped().widthPercent)
        assertTrue(TypographySettings.DEFAULT.isDefault)
        assertTrue(!TypographySettings.DEFAULT.withWidth(80).isDefault)
    }

    @Test
    fun `values are held to what the table offers`() {
        val o = TypeStyleOverride(weight = 1234, sizeSp = 99f, letterSpacingSp = -7f).clamped()
        assertEquals(900, o.weight)
        assertEquals(TypeStyleOverride.MAX_SIZE_SP, o.sizeSp)
        assertEquals(TypeStyleOverride.MIN_SPACING_SP, o.letterSpacingSp)
        assertEquals(500, TypeStyleOverride(weight = 460).clamped().weight)
        assertEquals(100, TypeStyleOverride(weight = 0).clamped().weight)
        assertEquals(15.5f, TypeStyleOverride(sizeSp = 15.4f).clamped().sizeSp)
        assertEquals(TypeStyleOverride.MIN_SIZE_SP, TypeStyleOverride(sizeSp = 1f).clamped().sizeSp)
    }

    @Test
    fun `setting a style back to its defaults removes it`() {
        val changed = TypographySettings.DEFAULT.with(TypeRole.TITLE_SMALL, TypeStyleOverride(weight = 800))
        assertEquals(1, changed.styles.size)
        assertTrue(changed.with(TypeRole.TITLE_SMALL, TypeStyleOverride()).styles.isEmpty())
    }

    @Test
    fun `the stored form round-trips and survives anything`() {
        val table = TypographySettings.DEFAULT
            .with(TypeRole.DISPLAY_LARGE, TypeStyleOverride(fontId = "/data/fonts/a.ttf", weight = 900))
            .with(TypeRole.BODY_SMALL, TypeStyleOverride(letterSpacingSp = 0.75f))
        assertEquals(table, TypographySettings.decode(TypographySettings.encode(table)))
        val narrow = table.withWidth(80)
        assertEquals(80, TypographySettings.decode(TypographySettings.encode(narrow)).widthPercent)
        assertEquals(TypographySettings.DEFAULT, TypographySettings.decode("not json"))
        assertEquals(TypographySettings.DEFAULT, TypographySettings.decode(null))
        // A style a later version adds, and a stray field, are left out, not a failure.
        val later = """{"styles":{"HEADLINE_HUGE":{"weight":700},"BODY_LARGE":{"weight":300,"colour":"red"}}}"""
        val read = TypographySettings.decode(later)
        assertEquals(setOf("BODY_LARGE"), read.styles.keys)
        assertEquals(300, read[TypeRole.BODY_LARGE].weight)
    }
}
