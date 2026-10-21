// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

/**
 * StepMania's older `.sm` simfile, rewritten as `.ssc`.
 *
 * Most packs on StepMania Online still ship `.sm`. The two formats share their
 * song tags (`#TITLE`, `#MUSIC`, `#OFFSET`, `#BPMS`, …); where they differ is
 * the chart. An `.sm` packs a whole chart into one tag, its fields split by
 * colons:
 *
 * ```
 * #NOTES:
 *      dance-single:
 *      Author or description:
 *      Challenge:
 *      11:
 *      0.5,0.4,0.3,0.2,0.1:
 * 1000
 * …;
 * ```
 *
 * and `.ssc` gives each field a tag of its own after a `#NOTEDATA:;`. That is
 * the whole conversion, so this is a rewrite of the text rather than a second
 * parser: everything downstream — [SscParser], the map editor, a pack written
 * back out — then deals in `.ssc` alone, as StepMania 5 itself does once it
 * has loaded an `.sm`.
 */
object SmConverter {

    /** True when [text] is an `.sm`: it has chart data, and no `.ssc` chart separators. */
    fun isSm(text: String): Boolean {
        val clean = stripComments(text)
        return NOTES_TAG.containsMatchIn(clean) && !NOTEDATA_TAG.containsMatchIn(clean)
    }

    /** [text] as `.ssc`. Text that is already `.ssc` comes back without its comments. */
    fun toSsc(text: String): String {
        val clean = stripComments(text)
        if (!isSm(clean)) return clean
        return buildString {
            appendLine("#VERSION:0.83;")
            for ((key, value) in tags(clean)) {
                if (key != "NOTES") {
                    if (key != "VERSION") appendLine("#$key:$value;")
                    continue
                }
                // Six fields: the last is the note data, which has no colons.
                val fields = value.split(':', limit = 6)
                if (fields.size < 6) continue
                appendLine()
                appendLine("#NOTEDATA:;")
                appendLine("#STEPSTYPE:${fields[0].trim()};")
                appendLine("#DESCRIPTION:${fields[1].trim()};")
                appendLine("#DIFFICULTY:${fields[2].trim()};")
                appendLine("#METER:${fields[3].trim()};")
                appendLine("#RADARVALUES:${fields[4].trim()};")
                appendLine("#NOTES:")
                append(fields[5].trim('\n', '\r', ' ', '\t'))
                appendLine()
                appendLine(";")
            }
        }
    }

    /**
     * [text] without `//` comments, as StepMania reads it. Charts carry them
     * between measures and between charts; left in, a comment holding a `#` or
     * a `;` would be read as a tag.
     */
    fun stripComments(text: String): String {
        if (!text.contains("//")) return text
        return text.lineSequence().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at < 0) line else line.substring(0, at)
        }
    }

    /** Every `#KEY:value;` in order, duplicates kept: an `.sm` has one `#NOTES` per chart. */
    private fun tags(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var index = 0
        while (index < text.length) {
            val hash = text.indexOf('#', index)
            if (hash < 0) break
            val colon = text.indexOf(':', hash + 1)
            if (colon < 0) break
            val semicolon = text.indexOf(';', colon + 1)
            if (semicolon < 0) break
            out += text.substring(hash + 1, colon).trim().uppercase() to text.substring(colon + 1, semicolon)
            index = semicolon + 1
        }
        return out
    }

    private val NOTES_TAG = Regex("""#NOTES\s*:""", RegexOption.IGNORE_CASE)
    private val NOTEDATA_TAG = Regex("""#NOTEDATA\s*:""", RegexOption.IGNORE_CASE)
}
