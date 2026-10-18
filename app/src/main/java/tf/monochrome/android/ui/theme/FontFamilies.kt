package tf.monochrome.android.ui.theme

import java.io.File
import java.util.Locale
import kotlin.math.abs

/** One file of an imported family: where it is, and which weight and slope it draws. */
data class FontCut(
    val file: File,
    /** The family it belongs to ([FontInspection.Font.groupFamily]). */
    val family: String,
    val weight: Int,
    val italic: Boolean,
    /** One file that renders every weight (`fvar`). */
    val variable: Boolean,
)

/**
 * An imported font as one family: every file in the library that names the
 * same family ("Inter Light", "Inter", "Inter Bold" are three cuts of Inter).
 */
data class ImportedFamily(val name: String, val cuts: List<FontCut>) {
    /**
     * The cut that stands for the family, and whose path is stored as the
     * chosen font: upright, a variable file over a static one, then the weight
     * nearest Regular (Medium before Light).
     */
    val representative: FontCut
        get() = cuts.minWith(
            compareBy<FontCut>({ it.italic }, { !it.variable }, { abs(it.weight - 400) }, { -it.weight }),
        )

    val files: List<File> get() = cuts.map { it.file }

    /** More than one file, so each is declared at its own weight rather than one at all of them. */
    val hasVariations: Boolean get() = cuts.size > 1
}

/**
 * Finds the variations of one font among the imported files.
 *
 * Fonts are often shipped as a file per weight and slope. Imported one by one,
 * each used to be its own font, and whichever was chosen was declared at every
 * weight: a title asking for Medium and a row asking for Regular got the same
 * file, so the interface's weights did nothing. Grouped by the family the files
 * declare, a chosen font brings its other cuts with it, each at its own weight.
 *
 * Pure apart from reading the files, so it runs in a JVM test against real fonts.
 */
object FontFamilies {

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** The family key: case, spacing and dashes do not make two families. */
    fun key(family: String): String =
        family.lowercase(Locale.ROOT).replace("-", " ").replace(Regex("\\s+"), " ").trim()

    /** [file] as a cut, or null when it cannot be read as a font. */
    fun cutOf(file: File): FontCut? {
        val font = FontFileInspector.inspect(file) as? FontInspection.Font ?: return null
        val family = font.groupFamily?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        return FontCut(file, family, font.weight, font.italic, font.variable)
    }

    /** [cuts] as families, each family's cuts upright first, light to heavy; families by name. */
    fun group(cuts: List<FontCut>): List<ImportedFamily> =
        cuts.groupBy { key(it.family) }
            .values
            .map { same ->
                val ordered = same.sortedWith(compareBy({ it.italic }, { it.weight }, { it.file.name }))
                ImportedFamily(ordered.first().family, ordered)
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    /** Every family in [dir]. Files that are not fonts are left out. */
    fun scan(dir: File): List<ImportedFamily> = group(fontFiles(dir).mapNotNull(::cutOf))

    /** The family [file] belongs to among the fonts beside it, or null when it is not a font. */
    fun familyOf(file: File): ImportedFamily? {
        val own = cutOf(file) ?: return null
        val dir = file.parentFile ?: return ImportedFamily(own.family, listOf(own))
        val siblings = fontFiles(dir)
            .filter { it.absolutePath != file.absolutePath }
            .mapNotNull(::cutOf)
            .filter { key(it.family) == key(own.family) }
        return group(listOf(own) + siblings).first()
    }

    private fun fontFiles(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in FONT_EXTENSIONS }
}
