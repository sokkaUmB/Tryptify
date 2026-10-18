package tf.monochrome.android.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The translations as they ship, read straight from the generated XML.
 *
 * `app/l10n/generate_strings.py` refuses to write a hole, a lost placeholder or
 * a missing plural form — but only for what goes through it. Somebody fixing a
 * typo in one `values-*` file by hand bypasses all of that, and every one of
 * these failures is silent on a device: Android falls back to English for a
 * missing string, and a placeholder that vanished from a translation simply
 * drops the number from the sentence. So the shipped files are checked too.
 */
class StringResourcesTest {

    private val res = File("src/main/res")
    private val folders = listOf("values-zh-rCN", "values-ja", "values-fr", "values-es", "values-tr", "values-de")

    /** Plural categories each language actually uses (CLDR). */
    private val pluralForms = mapOf(
        "values" to setOf("one", "other"),
        "values-zh-rCN" to setOf("other"),
        "values-ja" to setOf("other"),
        "values-fr" to setOf("one", "many", "other"),
        "values-es" to setOf("one", "many", "other"),
        "values-tr" to setOf("one", "other"),
        "values-de" to setOf("one", "other"),
    )

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(folder: String): Strings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$folder/strings.xml"))
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> if (e.getAttribute("translatable") != "false") strings[name] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Strings(strings, plurals)
    }

    private val placeholder = Regex("""%\d+\$[sdf]""")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    private val english by lazy { load("values") }

    @Test
    fun `every language has exactly the English strings`() {
        folders.forEach { folder ->
            val t = load(folder)
            assertEquals("$folder: strings differ from English", english.strings.keys, t.strings.keys)
            assertEquals("$folder: plurals differ from English", english.plurals.keys, t.plurals.keys)
        }
    }

    @Test
    fun `no translation loses or invents a placeholder`() {
        folders.forEach { folder ->
            val t = load(folder)
            val bad = english.strings.filter { (key, en) ->
                t.strings[key]?.let { placeholders(it) } != placeholders(en)
            }.keys
            assertTrue("$folder: placeholders differ in $bad", bad.isEmpty())
        }
    }

    @Test
    fun `every plural has its language's forms, and keeps the count`() {
        (listOf("values") + folders).forEach { folder ->
            val t = load(folder)
            t.plurals.forEach { (key, forms) ->
                assertEquals("$folder/$key quantities", pluralForms.getValue(folder), forms.keys)
                val want = placeholders(english.plurals.getValue(key).getValue("other"))
                forms.forEach { (q, text) ->
                    val got = placeholders(text)
                    // "one" may leave the number out; no form may invent one.
                    val ok = if (q == "one") want.containsAll(got) else got == want
                    assertTrue("$folder/$key[$q] has $got, English has $want", ok)
                }
            }
        }
    }

    /**
     * strings.tsv read as generate_strings.py reads it (Python's csv, tab
     * separated): a cell that starts with a quote runs to the closing quote,
     * newlines and doubled quotes inside it included.
     */
    private fun readTable(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var i = 0
        var quoted = false
        var atStart = true
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                atStart && c == '"' -> { quoted = true; atStart = false }
                c == '\t' -> { row.add(cell.toString()); cell.clear(); atStart = true; i++; continue }
                c == '\n' -> {
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf(); atStart = true
                    i++; continue
                }
                else -> { cell.append(c); atStart = false }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    /** generate_strings.py's android_escape, less the XML entities the parser has already undone. */
    private fun androidEscape(s: String): String {
        var t = s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        if (t.startsWith("@") || t.startsWith("?")) t = "\\" + t
        t = t.replace("\n", "\\n")
        if (t != t.trim()) t = "\"" + t + "\""
        return t
    }

    private fun frenchSpacing(s: String) = Regex(" ([:;?!])").replace(s, "\u00a0$1")

    @Test
    fun `the shipped XML is exactly what strings tsv generates`() {
        // A string written into strings.xml by hand works until somebody runs
        // the generator, which writes the files from the table alone: then it
        // silently vanishes, or a rewording silently reverts. 494 of Trypt
        // Mania's strings, two rewordings and eight removed strings were in that state.
        val table = readTable(File("l10n/strings.tsv").readText(Charsets.UTF_8))
        val header = table.first()
        val nonTranslatable = Regex("""NON_TRANSLATABLE = \{([^}]*)\}""")
            .find(File("l10n/generate_strings.py").readText())!!.groupValues[1]
            .split(",").map { it.trim().trim('\'') }.toSet()
        val columns = mapOf(
            "values" to "en", "values-zh-rCN" to "zh", "values-ja" to "ja", "values-fr" to "fr",
            "values-es" to "es", "values-tr" to "tr", "values-de" to "de",
        )
        val fix = "Edit app/l10n/strings.tsv and run python3 app/l10n/generate_strings.py; " +
            "strings.xml is generated and a hand edit is lost on the next run."
        columns.forEach { (folder, lang) ->
            val col = header.indexOf(lang)
            val expectStrings = mutableMapOf<String, String>()
            val expectPlurals = mutableMapOf<String, MutableMap<String, String>>()
            table.drop(1).forEach { row ->
                val key = row[0]
                val text = row.getOrElse(col) { "" }.let { if (lang == "fr") frenchSpacing(it) else it }
                when {
                    '#' in key -> {
                        val (base, q) = key.split('#')
                        if (q in pluralForms.getValue(folder)) {
                            expectPlurals.getOrPut(base) { mutableMapOf() }[q] = androidEscape(text)
                        }
                    }
                    key in nonTranslatable -> Unit // English only, and the loader skips it
                    else -> expectStrings[key] = androidEscape(text)
                }
            }
            val shipped = load(folder)
            assertEquals("$folder: strings not in the table, or table rows not shipped. $fix",
                expectStrings.keys, shipped.strings.keys)
            assertEquals("$folder: plurals not in the table, or table rows not shipped. $fix",
                expectPlurals.keys, shipped.plurals.keys)
            val reworded = expectStrings.filter { (k, v) -> shipped.strings[k] != v }.keys +
                expectPlurals.filter { (k, v) -> shipped.plurals[k] != v }.keys
            assertTrue("$folder: these differ from the table: $reworded. $fix", reworded.isEmpty())
        }
    }

    @Test
    fun `no unescaped apostrophe reaches aapt`() {
        // An unescaped ' in a resource is a build error at best and a
        // truncated string at worst; the generator escapes it, a hand edit
        // might not. The typographic ’ that French and Turkish use is fine.
        (listOf("values") + folders).forEach { folder ->
            val raw = File(res, "$folder/strings.xml").readText()
            val bad = Regex("""(?<!\\)'""").findAll(raw).count()
            assertEquals("$folder has $bad unescaped apostrophes", 0, bad)
        }
    }
}
