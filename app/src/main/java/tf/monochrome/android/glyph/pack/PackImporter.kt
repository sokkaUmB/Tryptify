// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.glyph.chart.SmConverter
import tf.monochrome.android.glyph.chart.SscParser

/**
 * Unpacks a StepMania pack ZIP — StepMania Online's, or one the app made —
 * into [root], one folder per pack.
 *
 * Streams: the ZIP is read once, front to back, straight from the download or
 * the picked file, and never held whole, so a gigabyte pack needs no gigabyte
 * of memory or a second copy on disk.
 *
 * ## A ZIP from the internet is untrusted
 *
 * * No entry is written outside the staging folder: a name with `..`, or one
 *   whose resolved path leaves the folder, is skipped ("zip slip").
 * * Every entry is bounded as it inflates, by what it is ([limitFor]), and the
 *   whole import by [maxTotalBytes] and by the free space left — so a small
 *   file that inflates to fill the phone is stopped part way, not after.
 * * Only what a pack needs is kept: charts, music, images, `Pack.ini`. Videos
 *   and everything else are skipped, and so is macOS's `__MACOSX` folder,
 *   whose `._song.sm` files would otherwise read as charts.
 *
 * ## All or nothing, per pack
 *
 * Everything lands in a hidden staging folder first. Only once the ZIP has
 * been read to the end are the songs checked — a chart that parses, with four
 * panel charts in it, and music it names — and each pack moved into place,
 * replacing an earlier import of the same pack. A failed or cancelled import
 * leaves nothing behind.
 */
class PackImporter(
    private val root: File,
    /** A song's length in seconds, for the song list; 0 when unknown. */
    private val durationOf: (File) -> Int = { 0 },
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    /** Free space to leave on the volume; the import stops before going under it. */
    private val reserveBytes: Long = 256L * 1024 * 1024,
) {

    class ImportedPack(val name: String, val folder: File, val songs: Int)

    class Result(val packs: List<ImportedPack>, val skippedSongs: Int) {
        val songs: Int get() = packs.sumOf { it.songs }
    }

    class PackImportException(message: String) : IOException(message)

    /**
     * Import the ZIP read from [input]. [nameHint] names the pack when the ZIP
     * holds song folders with no pack folder around them (usually the file's
     * name). [onBytes] gets the compressed bytes read so far, for progress.
     */
    fun import(
        input: InputStream,
        nameHint: String,
        onBytes: (Long) -> Unit = {},
        isActive: () -> Boolean = { true },
    ): Result {
        root.mkdirs()
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            extract(input, staging, onBytes, isActive)
            return install(staging, StepManiaPack.safeName(nameHint.removeSuffix(".zip"), "Imported pack"))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(input: InputStream, staging: File, onBytes: (Long) -> Unit, isActive: () -> Boolean) {
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        val counted = CountingInputStream(input, onBytes)
        var total = 0L
        var entries = 0
        var kept = 0
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(counted.buffered()).use { zip ->
            while (true) {
                if (!isActive()) throw PackImportException("The import was cancelled.")
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ENTRIES) throw PackImportException("That archive has too many files to be a pack.")
                if (entry.isDirectory) continue

                val segments = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (segments.isEmpty() || segments.any { it == ".." || it == "__MACOSX" }) continue
                val fileName = segments.last()
                if (fileName.startsWith("._")) continue
                val limit = limitFor(fileName) ?: continue

                val target = File(staging, segments.joinToString("/") { it.take(MAX_SEGMENT) })
                // Belt and braces: whatever the name held, the file is inside staging.
                if (!target.canonicalPath.startsWith(stagingPath)) continue
                ensureSpace()

                target.parentFile?.mkdirs()
                var written = 0L
                var tooBig = false
                target.outputStream().buffered().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > limit) {
                            tooBig = true
                            break
                        }
                        if (total > maxTotalBytes) throw PackImportException("That pack is too large to import.")
                        // A single song can be hundreds of megabytes: the space
                        // is checked as it is written, not only before.
                        if (written % SPACE_CHECK_BYTES < read) ensureSpace()
                        out.write(buffer, 0, read)
                    }
                }
                // An entry past its bound is dropped, not truncated: half a
                // song plays wrong and half a chart reads wrong.
                if (tooBig) target.delete() else kept += 1
            }
        }
        if (kept == 0) throw PackImportException("That file is not a StepMania pack: it has no charts or music in it.")
    }

    private fun ensureSpace() {
        if (root.usableSpace < reserveBytes) throw PackImportException("The phone is out of space for this pack.")
    }

    private fun install(staging: File, nameHint: String): Result {
        val songFolders = staging.walkTopDown()
            .filter { it.isDirectory }
            .filter { folder -> folder.listFiles()?.any { it.isFile && StepManiaPack.extension(it.name) in StepManiaPack.CHART_EXTENSIONS } == true }
            .toList()

        // Group song folders by pack: the folder around each, or the hint when
        // the ZIP is a bare song or a bare set of songs.
        val byPack = LinkedHashMap<String, MutableList<File>>()
        for (folder in songFolders) {
            val depth = folder.relativeTo(staging).invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }.size
            val pack = if (depth >= 2) folder.parentFile!!.name else nameHint
            byPack.getOrPut(StepManiaPack.safeName(pack, nameHint)) { ArrayList() } += folder
        }

        var skipped = 0
        val packs = ArrayList<ImportedPack>()
        for ((packName, folders) in byPack) {
            val packFolder = File(staging, ".ready-${packs.size}-${packName.hashCode()}")
            packFolder.mkdirs()
            val songs = ArrayList<PackIndex.Song>()
            val usedNames = HashSet<String>()
            for (folder in folders) {
                val song = prepareSong(folder)
                if (song == null) {
                    skipped += 1
                    continue
                }
                var name = StepManiaPack.safeName(if (folder == staging) song.title else folder.name)
                while (!usedNames.add(name.lowercase())) name = "$name (2)"
                val destination = File(packFolder, name)
                if (folder == staging) {
                    // A bare song at the top of the ZIP: its files move, since
                    // the folder they are in holds the destination too.
                    destination.mkdirs()
                    folder.listFiles()?.filter { it.isFile }?.forEach { it.renameTo(File(destination, it.name)) }
                } else if (!folder.renameTo(destination)) {
                    folder.copyRecursively(destination, overwrite = true)
                }
                songs += song.copy(folder = name)
            }
            if (songs.isEmpty()) continue

            // The pack's own Pack.ini and banner, when it has them, go with it.
            folders.first().parentFile?.takeIf { it != staging && it.startsWith(staging) }?.listFiles()
                ?.filter { it.isFile && (it.name.equals(StepManiaPack.PACK_INI, true) || StepManiaPack.extension(it.name) in StepManiaPack.IMAGE_EXTENSIONS) }
                ?.forEach { it.copyTo(File(packFolder, it.name), overwrite = true) }

            File(packFolder, StepManiaPack.INDEX_FILE).writeText(JSON.encodeToString(PackIndex(packName, songs.sortedBy { it.title.lowercase() })))

            // Replace an earlier import of the same pack, as a whole.
            val destination = File(root, packName)
            if (destination.exists()) destination.deleteRecursively()
            if (!packFolder.renameTo(destination)) {
                packFolder.copyRecursively(destination, overwrite = true)
            }
            packs += ImportedPack(packName, destination, songs.size)
        }
        if (packs.isEmpty()) {
            throw PackImportException("No song in that pack has a four-panel (dance-single) chart with its music.")
        }
        return Result(packs, skipped)
    }

    /**
     * Check one song folder and make its chart an `.ssc`. Null when it cannot
     * be played: no chart that reads, no four-panel chart, or no music.
     */
    private fun prepareSong(folder: File): PackIndex.Song? {
        val chartFile = StepManiaPack.CHART_EXTENSIONS.firstNotNullOfOrNull { ext ->
            folder.listFiles()?.filter { it.isFile && StepManiaPack.extension(it.name) == ext }?.minByOrNull { it.name }
        } ?: return null
        val text = runCatching { chartFile.readText() }.getOrNull() ?: return null
        val ssc = SmConverter.toSsc(text)
        val simfile = runCatching { SscParser.parse(ssc) }.getOrNull() ?: return null
        val music = StepManiaPack.music(folder, ssc) ?: return null

        // An .sm is kept as it came, with the .ssc the app reads beside it.
        val chart = if (StepManiaPack.extension(chartFile.name) == "ssc") chartFile else {
            File(folder, chartFile.nameWithoutExtension + ".ssc").also { it.writeText(ssc) }
        }
        return PackIndex.Song(
            folder = folder.name,
            chart = chart.name,
            music = music.name,
            art = StepManiaPack.art(folder, ssc)?.name,
            title = simfile.title,
            artist = simfile.artist,
            durationSeconds = runCatching { durationOf(music) }.getOrDefault(0),
            difficulties = simfile.availableDifficulties.map { it.name },
            bpm = simfile.timing.startBpm,
        )
    }

    /** The most a file of this kind may inflate to; null for a file a pack does not need. */
    private fun limitFor(name: String): Long? = when (StepManiaPack.extension(name)) {
        in StepManiaPack.CHART_EXTENSIONS -> 16L * MB
        in StepManiaPack.AUDIO_EXTENSIONS -> 512L * MB
        in StepManiaPack.IMAGE_EXTENSIONS -> 32L * MB
        "ini" -> if (name.equals(StepManiaPack.PACK_INI, ignoreCase = true)) 64L * 1024 else null
        else -> null
    }

    private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        private fun report(n: Long) {
            count += n
            onBytes(count)
        }
    }

    companion object {
        private const val MB = 1024L * 1024
        private const val MAX_ENTRIES = 100_000
        private const val MAX_SEGMENT = 120
        private const val SPACE_CHECK_BYTES = 16L * MB
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /** Read the index [PackImporter] wrote for the pack in [folder], or null. */
        fun readIndex(folder: File): PackIndex? = runCatching {
            JSON.decodeFromString<PackIndex>(File(folder, StepManiaPack.INDEX_FILE).readText())
        }.getOrNull()
    }
}
