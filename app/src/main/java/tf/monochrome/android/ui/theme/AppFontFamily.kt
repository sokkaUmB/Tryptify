package tf.monochrome.android.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
 * A bundled font, or an imported font that is one file, is declared as five
 * weights over that file. That reads oddly but is correct for variable fonts,
 * which every bundled font is: Compose derives the `wght` variation axis from
 * the declared [FontWeight], so one file genuinely renders Light through Bold.
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
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            val path = BundledFonts.assetPathOf(fontId)
            FontFamily(
                Font(path, context.assets, FontWeight.Light),
                Font(path, context.assets, FontWeight.Normal),
                Font(path, context.assets, FontWeight.Medium),
                Font(path, context.assets, FontWeight.SemiBold),
                Font(path, context.assets, FontWeight.Bold),
            )
        } else {
            val file = File(fontId)
            if (!file.exists()) return null
            importedFontFamily(file)
        }
    }.getOrNull()
}

/** The lyric font: [path] and its variations, or null when it is missing or unreadable. */
fun loadImportedFontFamily(path: String): FontFamily? = runCatching {
    File(path).takeIf { it.exists() }?.let(::importedFontFamily)
}.getOrNull()

private fun importedFontFamily(file: File): FontFamily {
    val family = FontFamilies.familyOf(file)
    if (family == null || !family.hasVariations) {
        return FontFamily(FIVE_WEIGHTS.map { Font(file, it) })
    }
    // Each cut at its weight and slope; a variable cut at all five. Compose
    // matches a weight the family lacks to the nearest it has, as CSS does.
    return FontFamily(
        family.cuts.flatMap { cut ->
            val style = if (cut.italic) FontStyle.Italic else FontStyle.Normal
            if (cut.variable) FIVE_WEIGHTS.map { Font(cut.file, it, style) }
            else listOf(Font(cut.file, FontWeight(cut.weight.coerceIn(1, 1000)), style))
        },
    )
}

private val FIVE_WEIGHTS = listOf(
    FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
)
