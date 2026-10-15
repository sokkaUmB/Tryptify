// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.SmConverter
import tf.monochrome.android.glyph.chart.SscParser

/**
 * StepMania packs in and out: the `.sm` rewrite, the importer's guards, and a
 * pack the app writes coming back in through the same importer.
 */
class StepManiaPackTest {

    private val root: File = Files.createTempDirectory("packs").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** An `.sm` as StepMania Online's packs carry them: a comment, four-panel and eight-panel charts. */
    private val sm = """
        #TITLE:Feel So High;
        #ARTIST:Dain-Ja;
        #MUSIC:Feel So High.ogg;
        #BANNER:FSH-BN.png;
        #OFFSET:-0.226;
        #BPMS:0.000=178.000;
        #STOPS:;
        //--------------- dance-single -  ----------------
        #NOTES:
             dance-single:
             Lemy:
             Challenge:
             11:
             0,0,0,0,0:
        1000
        0010
        0000
        0001
        ,
        2000
        0000
        3100
        0000
        ;
        //--------------- dance-double -  ----------------
        #NOTES:
             dance-double:
             :
             Hard:
             9:
             0,0,0,0,0:
        10000001
        00000000
        00000000
        00000000
        ;
        #NOTES:
             dance-single:
             :
             Light:
             3:
             0,0,0,0,0:
        1000
        0000
        0000
        0000
        ;
    """.trimIndent()

    private val ssc = """
        #VERSION:0.83;
        #TITLE:Own Song;
        #ARTIST:Me;
        #MUSIC:own.mp3;
        #OFFSET:0.000;
        #BPMS:0.000=120.000;
        #NOTEDATA:;
        #STEPSTYPE:dance-single;
        #DIFFICULTY:Medium;
        #METER:6;
        #NOTES:
        1000
        0100
        0010
        0001
        ;
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun importer(maxTotal: Long = Long.MAX_VALUE) =
        PackImporter(root, durationOf = { 95 }, maxTotalBytes = maxTotal, reserveBytes = 0)

    private val audio = ByteArray(2048) { it.toByte() }

    // ── .sm ─────────────────────────────────────────────────────────────

    @Test
    fun smBecomesSscWithItsFourPanelChartsOnly() {
        assertTrue(SmConverter.isSm(sm))
        val converted = SmConverter.toSsc(sm)
        assertFalse("comments are gone", converted.contains("//"))
        assertTrue(converted.contains("#NOTEDATA:;"))

        val simfile = SscParser.parse(sm)!!
        assertEquals("Feel So High", simfile.title)
        assertEquals("Feel So High.ogg", simfile.musicFileName)
        assertEquals(178f, simfile.timing.startBpm, 1e-3f)
        // Challenge and the old "Light" (Easy); the dance-double Hard is left out.
        assertEquals(listOf(StepManiaDifficulty.EASY, StepManiaDifficulty.CHALLENGE), simfile.availableDifficulties)
        val challenge = simfile.chart(StepManiaDifficulty.CHALLENGE)!!
        assertEquals(11, challenge.meter)
        assertTrue(challenge.notes.any { it.type == GlyphNoteType.HOLD })
        // An .ssc is not touched by the conversion, beyond its comments.
        assertFalse(SmConverter.isSm(ssc))
        assertEquals(ssc, SmConverter.toSsc(ssc))
    }

    @Test
    fun anEditBesideARealChallengeLosesToIt() {
        val text = ssc.replace("#DIFFICULTY:Medium;", "#DIFFICULTY:Edit;") +
            "\n#NOTEDATA:;\n#STEPSTYPE:dance-single;\n#DIFFICULTY:Challenge;\n#METER:12;\n#NOTES:\n0001\n0010\n0100\n1000\n;"
        val simfile = SscParser.parse(text)!!
        assertEquals(listOf(StepManiaDifficulty.CHALLENGE), simfile.availableDifficulties)
        assertEquals(12, simfile.chart(StepManiaDifficulty.CHALLENGE)!!.meter)
    }

    // ── importing ───────────────────────────────────────────────────────

    @Test
    fun aStepManiaOnlinePackImportsWithItsSongsAndIndex() {
        val pack = zip(
            "STRICTLY HARDCORE/" to ByteArray(0),
            "STRICTLY HARDCORE/Pack.ini" to "[Group]\nDisplayTitle=Strictly".toByteArray(),
            "STRICTLY HARDCORE/Feel So High/Feel So High.sm" to sm.toByteArray(),
            "STRICTLY HARDCORE/Feel So High/Feel So High.ogg" to audio,
            "STRICTLY HARDCORE/Feel So High/FSH-BN.png" to ByteArray(10),
            "STRICTLY HARDCORE/Feel So High/intro.avi" to ByteArray(100),
            "STRICTLY HARDCORE/Own/own.ssc" to ssc.toByteArray(),
            "STRICTLY HARDCORE/Own/own.mp3" to audio,
            "__MACOSX/STRICTLY HARDCORE/Own/._own.ssc" to ByteArray(5),
        )
        var read = 0L
        val result = importer().import(ByteArrayInputStream(pack), "download.zip", onBytes = { read = it })

        assertEquals(pack.size.toLong(), read)
        assertEquals(1, result.packs.size)
        assertEquals("STRICTLY HARDCORE", result.packs[0].name)
        assertEquals(2, result.songs)
        val folder = File(root, "STRICTLY HARDCORE")
        assertTrue(File(folder, "Pack.ini").isFile)
        assertFalse("videos are not kept", File(folder, "Feel So High/intro.avi").exists())
        assertTrue("the .sm is kept, with an .ssc beside it", File(folder, "Feel So High/Feel So High.sm").isFile)
        assertNull(root.listFiles()!!.firstOrNull { it.name.startsWith(".staging") })

        val index = PackImporter.readIndex(folder)!!
        val feel = index.songs.first { it.title == "Feel So High" }
        assertEquals("Feel So High.ssc", feel.chart)
        assertEquals("Feel So High.ogg", feel.music)
        assertEquals("FSH-BN.png", feel.art)
        assertEquals(95, feel.durationSeconds)
        assertNotNull(SscParser.parse(File(folder, "Feel So High/${feel.chart}").readText()))
    }

    // ── songs already unpacked in a folder ──────────────────────────────

    private fun folder(vararg entries: Pair<String, ByteArray>) = entries.map { (path, bytes) ->
        PackImporter.SourceFile(path.split('/'), bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    @Test
    fun stepManiasSongsFolderImportsEachPackOnItsOwn() {
        // StepMania 5/Songs on a phone: packs of song folders, and things that
        // are not songs beside them.
        val files = folder(
            "Dance Dance Revolution Ultramix 2/Altitude/Altitude.sm" to sm.toByteArray(),
            "Dance Dance Revolution Ultramix 2/Altitude/Altitude.ogg" to audio,
            "Dance Dance Revolution Ultramix 2/Altitude/preview.avi" to ByteArray(50),
            "dj Manix Originals/Own/own.ssc" to ssc.toByteArray(),
            "dj Manix Originals/Own/own.mp3" to audio,
            "dj Manix Originals/readme.txt" to ByteArray(5),
            "__MACOSX/dj Manix Originals/Own/._own.ssc" to ByteArray(5),
        )
        var lastCopied = 0L
        var lastTotal = 0L
        val result = importer().importFolder(files, "Songs", onProgress = { copied, total ->
            lastCopied = copied
            lastTotal = total
        })

        assertEquals(listOf("Dance Dance Revolution Ultramix 2", "dj Manix Originals"), result.packs.map { it.name })
        assertEquals(2, result.songs)
        assertEquals(0, result.skippedSongs)
        // Every byte of the song folders, the video included (counted, then dropped).
        assertEquals(lastTotal, lastCopied)
        val ultramix = File(root, "Dance Dance Revolution Ultramix 2")
        assertTrue("the .sm is kept, with an .ssc beside it", File(ultramix, "Altitude/Altitude.ssc").isFile)
        assertFalse("videos are not kept", File(ultramix, "Altitude/preview.avi").exists())
        assertEquals("Altitude.ogg", PackImporter.readIndex(ultramix)!!.songs.single().music)
        assertNull(root.listFiles()!!.firstOrNull { it.name.startsWith(".staging") })
    }

    @Test
    fun aPickedPackFolderIsThatPack() {
        val files = folder(
            "Altitude/Altitude.sm" to sm.toByteArray(),
            "Altitude/Altitude.ogg" to audio,
            "Own/own.ssc" to ssc.toByteArray(),
            "Own/own.mp3" to audio,
        )
        val result = importer().importFolder(files, "Dance Dance Revolution Ultramix 2")
        assertEquals("Dance Dance Revolution Ultramix 2", result.packs.single().name)
        assertEquals(2, result.songs)
    }

    @Test
    fun aPickedSongFolderIsAPackOfOne() {
        val files = folder("Altitude.sm" to sm.toByteArray(), "Altitude.ogg" to audio)
        val result = importer().importFolder(files, "Altitude")
        assertEquals("Altitude", result.packs.single().name)
        assertEquals(1, result.songs)
    }

    @Test
    fun aPackWithNothingPlayableIsSkippedAndTheRestStillImport() {
        val files = folder(
            "Broken/No Music/nomusic.sm" to sm.toByteArray(),
            "Good/Own/own.ssc" to ssc.toByteArray(),
            "Good/Own/own.mp3" to audio,
        )
        val result = importer().importFolder(files, "Songs")
        assertEquals("Good", result.packs.single().name)
        assertEquals(1, result.skippedSongs)
        assertFalse(File(root, "Broken").exists())
    }

    @Test
    fun aFolderWithNoChartsIsRefused() {
        val files = folder("Themes/default/metrics.ini" to ByteArray(10), "music.ogg" to audio)
        val failure = runCatching { importer().importFolder(files, "StepMania 5") }.exceptionOrNull()
        assertTrue(failure is PackImporter.PackImportException)
        assertTrue(root.listFiles().orEmpty().none { !it.name.startsWith(".") })
    }

    @Test
    fun songsWithoutAPackFolderTakeTheFileName() {
        val bare = zip("own.ssc" to ssc.toByteArray(), "own.mp3" to audio)
        val result = importer().import(ByteArrayInputStream(bare), "My Song.zip")
        assertEquals("My Song", result.packs.single().name)
        val index = PackImporter.readIndex(File(root, "My Song"))!!
        assertEquals("Own Song", index.songs.single().title)
        assertTrue(File(root, "My Song/${index.songs.single().folder}/own.ssc").isFile)
    }

    @Test
    fun nothingIsWrittenOutsideTheFolder() {
        val evil = zip(
            "../../escaped.ssc" to ssc.toByteArray(),
            "Pack/../../escaped.mp3" to audio,
            "Pack/Song/own.ssc" to ssc.toByteArray(),
            "Pack/Song/own.mp3" to audio,
        )
        importer().import(ByteArrayInputStream(evil), "evil.zip")
        assertFalse(File(root.parentFile, "escaped.ssc").exists())
        assertFalse(File(root.parentFile, "escaped.mp3").exists())
        assertTrue(File(root, "Pack/Song/own.ssc").isFile)
    }

    @Test
    fun aPackThatInflatesPastTheLimitIsStoppedAndLeavesNothing() {
        val bomb = zip("Pack/Song/own.ssc" to ssc.toByteArray(), "Pack/Song/own.mp3" to ByteArray(4 * 1024 * 1024))
        assertThrows(PackImporter.PackImportException::class.java) {
            importer(maxTotal = 1024 * 1024).import(ByteArrayInputStream(bomb), "bomb.zip")
        }
        assertTrue("staging is cleaned up", root.listFiles().isNullOrEmpty())
    }

    @Test
    fun aPackWithNoFourPanelChartIsRefused() {
        val doubles = sm.replace("dance-single", "dance-double")
        val pack = zip("Pack/Song/song.sm" to doubles.toByteArray(), "Pack/Song/Feel So High.ogg" to audio)
        assertThrows(PackImporter.PackImportException::class.java) {
            importer().import(ByteArrayInputStream(pack), "doubles.zip")
        }
        assertFalse(File(root, "Pack").exists())
    }

    @Test
    fun reimportingAPackReplacesIt() {
        val first = zip("Pack/A/own.ssc" to ssc.toByteArray(), "Pack/A/own.mp3" to audio)
        val second = zip("Pack/B/own.ssc" to ssc.replace("Own Song", "Other").toByteArray(), "Pack/B/own.mp3" to audio)
        importer().import(ByteArrayInputStream(first), "p.zip")
        importer().import(ByteArrayInputStream(second), "p.zip")
        assertFalse(File(root, "Pack/A").exists())
        assertEquals(listOf("Other"), PackImporter.readIndex(File(root, "Pack"))!!.songs.map { it.title })
    }

    // ── our own packs ───────────────────────────────────────────────────

    @Test
    fun aPackTheAppWritesImportsBackLikeAnyOther() {
        val bytes = ByteArrayOutputStream()
        ZipPackSink(bytes).use { sink ->
            PackWriter.writeSong(sink, "Tryptify", "Own Song", "Me", ssc) { ByteArrayInputStream(audio) }
            PackWriter.writeSong(sink, "Tryptify", "Second", "", ssc.replace("Own Song", "Second")) { ByteArrayInputStream(audio) }
        }
        val result = importer().import(ByteArrayInputStream(bytes.toByteArray()), "shared.zip")

        assertEquals("Tryptify", result.packs.single().name)
        assertEquals(2, result.songs)
        val folder = File(root, "Tryptify")
        assertTrue(File(folder, "Pack.ini").readText().contains("DisplayTitle=Tryptify"))
        val song = File(folder, "Own Song - Me")
        assertTrue(File(song, "Own Song - Me.ssc").isFile)
        assertTrue(File(song, "own.mp3").readBytes().contentEquals(audio))
    }

    @Test
    fun writingAFolderPackKeepsTheMusicAndRewritesTheChart() {
        val out = Files.createTempDirectory("songs").toFile()
        try {
            val sink = FolderPackSink(out)
            var opened = 0
            PackWriter.writeSong(sink, "Tryptify", "Own Song", "Me", ssc) { opened++; ByteArrayInputStream(audio) }
            PackWriter.writeSong(sink, "Tryptify", "Own Song", "Me", ssc.replace("#METER:6;", "#METER:7;")) { opened++; ByteArrayInputStream(audio) }
            assertEquals("the music is copied once", 1, opened)
            assertTrue(File(out, "Tryptify/Own Song - Me/Own Song - Me.ssc").readText().contains("#METER:7;"))
            assertFalse(out.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun aDownloadIsNamedByItsResponse() {
        assertEquals(
            "East Coast Stamina 15 - Upper Marathon.zip",
            PackImportWorker.fileNameOf("attachment; filename=\"East Coast Stamina 15 - Upper Marathon.zip\""),
        )
        assertEquals("Pack é.zip", PackImportWorker.fileNameOf("attachment; filename*=UTF-8''Pack%20%C3%A9.zip"))
        assertNull(PackImportWorker.fileNameOf(null))
    }

    @Test
    fun namesAreSafeOnEverySystem() {
        assertEquals("AC DC Live", StepManiaPack.safeName("AC/DC: Live"))
        assertEquals("Untitled", StepManiaPack.safeName("..."))
        assertEquals("x", StepManiaPack.safeName("..x.."))
        assertTrue(StepManiaPack.safeName("a".repeat(500)).length <= 100)
    }
}
