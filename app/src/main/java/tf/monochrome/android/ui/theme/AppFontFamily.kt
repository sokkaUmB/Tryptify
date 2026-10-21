package tf.monochrome.android.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Builds the [FontFamily] for a stored font id, or null to mean "use the
 * built-in default" (Inter).
 *
 * Two id shapes, because there are two kinds of font:
 *  - `asset:fonts/x.ttf` — one Tryptify ships, read out of the APK.
 *  - an absolute path    — one the user imported into `filesDir/custom_fonts`.
 *
 * A bundled font, or an imported font that is one file, is declared as all
 * nine weights, Thin to Black, over that file. That reads oddly but is correct for variable fonts,
 * which every bundled font is: Compose derives the `wght` variation axis from
 * the declared [FontWeight], so one file genuinely renders Thin through Black.
 * A static file then draws every weight the same, as imported fonts always did.
 *
 * An imported font with variations — other files in the library naming the
 * same family ([FontFamilies]) — is declared as all of them, each at its own
 * weight and slope, so Medium is the Medium file and Bold the Bold one. A path
 * to any of its files stands for the whole family.
 *
 * Returns null rather than throwing on a missing or unreadable font, so a
 * deleted file or a bad import falls back to the default instead of taking the
 * whole interface down.
 */
fun loadAppFontFamily(context: Context, fontId: String?): FontFamily? {
    if (fontId.isNullOrBlank()) return null
    if (fontId == INTER_FONT_ID) return InterFontFamily
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            val path = BundledFonts.assetPathOf(fontId)
            FontFamily(ALL_WEIGHTS.map { Font(path, context.assets, it) })
        } else {
            val file = File(fontId)
            if (!file.exists()) return null
            importedFontFamily(file)
        }
    }.getOrNull()
}

/**
 * [fontId] at the one [weight] a sample of it is drawn at, or null as
 * [loadAppFontFamily] gives it: a font picker's chip needs its name in that
 * font, not nine weights of it. An imported family is drawn from its upright
 * cut nearest that weight (a variable one draws it exactly).
 */
fun loadSampleFontFamily(context: Context, fontId: String?, weight: FontWeight): FontFamily? {
    if (fontId.isNullOrBlank()) return null
    if (fontId == INTER_FONT_ID) return InterFontFamily
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            FontFamily(Font(BundledFonts.assetPathOf(fontId), context.assets, weight))
        } else {
            val file = File(fontId)
            if (!file.exists()) return null
            val cut = FontFamilies.familyOf(file)?.cuts
                ?.filterNot { it.italic }
                ?.minByOrNull { if (it.variable) 0 else kotlin.math.abs(it.weight - weight.weight) }
            FontFamily(Font(cut?.file ?: file, weight))
        }
    }.getOrNull()
}

/**
 * A font as a style draws it at a letter width: [family], already as narrow
 * or wide as its own `wdth` axis can draw, and [scaleX], the squeeze left to
 * apply to its glyphs (1 when the axis reached the width; the whole width
 * for a font with no such axis).
 */
class WidthFont(val family: FontFamily, val scaleX: Float)

/**
 * [fontId] (null or Inter's id: Inter) with its letters [widthPercent] wide.
 * A variable font with a width axis is declared at that width, as far as its
 * axis goes; whatever it cannot reach is left to [WidthFont.scaleX].
 */
fun loadWidthFont(context: Context, fontId: String?, widthPercent: Int): WidthFont {
    val wanted = widthPercent.toFloat()
    val inter = WidthFont(InterFontFamily, wanted / 100f)
    if (fontId.isNullOrBlank() || fontId == INTER_FONT_ID) return inter
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            val path = BundledFonts.assetPathOf(fontId)
            val axis = assetAxes(context, path)["wdth"]
            if (axis == null || widthPercent == 100) {
                WidthFont(loadAppFontFamily(context, fontId) ?: InterFontFamily, if (axis == null) wanted / 100f else 1f)
            } else {
                val drawn = wanted.coerceIn(axis.start, axis.endInclusive)
                WidthFont(
                    FontFamily(ALL_WEIGHTS.map { Font(path, context.assets, it, FontStyle.Normal, widthSettings(it, FontStyle.Normal, drawn)) }),
                    wanted / drawn,
                )
            }
        } else {
            val file = File(fontId)
            if (!file.exists()) return inter
            importedWidthFont(file, wanted)
        }
    }.getOrDefault(inter)
}

/** An imported font at a width: its variable cuts with a width axis draw it, any other is squeezed. */
private fun importedWidthFont(file: File, wanted: Float): WidthFont {
    val cuts = FontFamilies.familyOf(file)?.cuts.orEmpty()
    val axis = cuts.mapNotNull { it.widthAxis }.reduceOrNull { a, b -> maxOf(a.start, b.start)..minOf(a.endInclusive, b.endInclusive) }
    // Only when every cut can draw the width: a static cut beside them could not.
    if (wanted == 100f || cuts.isEmpty() || axis == null || cuts.any { it.widthAxis == null }) {
        return WidthFont(importedFontFamily(file), wanted / 100f)
    }
    val drawn = wanted.coerceIn(axis.start, axis.endInclusive)
    return WidthFont(
        FontFamily(
            cuts.flatMap { cut ->
                val style = if (cut.italic) FontStyle.Italic else FontStyle.Normal
                ALL_WEIGHTS.map { Font(cut.file, it, style, widthSettings(it, style, drawn)) }
            },
        ),
        wanted / drawn,
    )
}

private fun widthSettings(weight: FontWeight, style: FontStyle, width: Float) = FontVariation.Settings(
    FontVariation.weight(weight.weight),
    FontVariation.italic(if (style == FontStyle.Italic) 1f else 0f),
    FontVariation.width(width),
)

/** A bundled font's variation axes, read once per file. */
private val assetAxesCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, ClosedFloatingPointRange<Float>>>()

private fun assetAxes(context: Context, path: String): Map<String, ClosedFloatingPointRange<Float>> =
    assetAxesCache.getOrPut(path) {
        runCatching {
            val bytes = context.assets.open(path).use { it.readBytes() }
            (FontFileInspector.inspect(bytes) as? FontInspection.Font)?.axes.orEmpty()
        }.getOrDefault(emptyMap())
    }

/**
 * Inter by name: what a typography-table style stores to ask for the default
 * font while the main font is another. The main font itself stores no id for it.
 */
const val INTER_FONT_ID = "builtin:inter"

/** The lyric font: [path] and its variations, or null when it is missing or unreadable. */
fun loadImportedFontFamily(path: String): FontFamily? = runCatching {
    File(path).takeIf { it.exists() }?.let(::importedFontFamily)
}.getOrNull()

private fun importedFontFamily(file: File): FontFamily {
    val family = FontFamilies.familyOf(file)
    if (family == null || !family.hasVariations) {
        return FontFamily(ALL_WEIGHTS.map { Font(file, it) })
    }
    // Each cut at its weight and slope; a variable cut at all nine. Compose
    // matches a weight the family lacks to the nearest it has, as CSS does.
    return FontFamily(
        family.cuts.flatMap { cut ->
            val style = if (cut.italic) FontStyle.Italic else FontStyle.Normal
            if (cut.variable) ALL_WEIGHTS.map { Font(cut.file, it, style) }
            else listOf(Font(cut.file, FontWeight(cut.weight.coerceIn(1, 1000)), style))
        },
    )
}

/**
 * Thin to Black, every weight the typography table offers. A variable font
 * draws each from its `wght` axis (clamped to the axis the font has); a
 * static file declared at all nine draws them all the same.
 */
private val ALL_WEIGHTS = (1..9).map { FontWeight(it * 100) }
