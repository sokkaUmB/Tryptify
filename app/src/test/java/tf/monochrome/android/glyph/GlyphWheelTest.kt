// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphChartStats
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming
import tf.monochrome.android.glyph.chart.bpmRange
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.data.GlyphSongSort
import tf.monochrome.android.glyph.data.GlyphSortKey
import tf.monochrome.android.glyph.data.GlyphWheel

/** The music wheel's folders, and the Groove Radar and density graph beside it. */
class GlyphWheelTest {

    private fun song(
        title: String,
        pack: String? = null,
        bpm: Float? = null,
        seconds: Int = 120,
        mapped: Boolean = true,
        artist: String = "A",
    ) = GlyphSong(
        trackId = title,
        chartId = "c-$title",
        title = title,
        artist = artist,
        filePath = "/x/$title.mp3",
        artworkUri = null,
        durationSeconds = seconds,
        codec = AudioCodec.MP3,
        bpm = bpm,
        difficulties = emptyList(),
        chartState = if (mapped) GlyphChartState.READY else GlyphChartState.NOT_GENERATED,
        pack = pack,
    )

    private val library = listOf(
        song("Zebra", pack = "Beta"),
        song("Mango"),
        song("Apple", pack = "Alpha"),
        song("Cherry", pack = "Alpha", mapped = false),
        song("Kiwi"),
    )

    // ── folders ─────────────────────────────────────────────────────────

    @Test
    fun packSortMakesTheLibraryThenEachPackAToZ() {
        val sections = GlyphWheel.sections(library, GlyphSongSort(GlyphSortKey.PACK))
        assertEquals(listOf(GlyphWheel.LIBRARY, "Alpha", "Beta"), sections.map { it.name })
        assertEquals(listOf("Kiwi", "Mango"), sections[0].songs.map { it.title })
        assertEquals(listOf("Apple", "Cherry"), sections[1].songs.map { it.title })
        assertEquals(1, sections[1].charted)
    }

    @Test
    fun descendingTurnsTheFoldersRound() {
        val sections = GlyphWheel.sections(library, GlyphSongSort(GlyphSortKey.PACK, ascending = false))
        assertEquals(listOf("Beta", "Alpha", GlyphWheel.LIBRARY), sections.map { it.name })
    }

    @Test
    fun theOtherSortsFolderAsStepManiaDoes() {
        assertEquals("120 – 129 BPM", GlyphWheel.sectionOf(song("a", bpm = 128.5f), GlyphSortKey.BPM))
        assertEquals("BPM unknown", GlyphWheel.sectionOf(song("a"), GlyphSortKey.BPM))
        assertEquals("3:00 – 4:00", GlyphWheel.sectionOf(song("a", seconds = 185), GlyphSortKey.DURATION))
        assertEquals("A", GlyphWheel.sectionOf(song("apple"), GlyphSortKey.NAME))
        assertEquals("#", GlyphWheel.sectionOf(song("123"), GlyphSortKey.NAME))
        assertEquals("Not mapped yet", GlyphWheel.sectionOf(song("a", mapped = false), GlyphSortKey.MAPPED))
        assertEquals(GlyphWheel.LIBRARY, GlyphWheel.sectionOf(song("a"), GlyphSortKey.PACK))
    }

    @Test
    fun onlyTheOpenFoldersSongsAreOnTheWheel() {
        val sections = GlyphWheel.sections(library, GlyphSongSort(GlyphSortKey.PACK))
        val closed = GlyphWheel.items(sections, open = null)
        assertEquals(3, closed.size)
        assertTrue(closed.all { it is GlyphWheel.Item.Folder && !it.open })

        val open = GlyphWheel.items(sections, open = "Alpha")
        assertEquals(
            listOf("folder:${GlyphWheel.LIBRARY}", "folder:Alpha", "song:Apple", "song:Cherry", "folder:Beta"),
            open.map { it.key },
        )
        assertTrue((open[1] as GlyphWheel.Item.Folder).open)
        assertEquals("Alpha", (open[2] as GlyphWheel.Item.Song).section)
    }

    @Test
    fun aSearchIsOneFolderOfItsResults() {
        val found = GlyphWheel.sections(library, GlyphSongSort(GlyphSortKey.PACK), query = " an")
        assertEquals(1, found.size)
        assertEquals(GlyphWheel.searchName(" an"), found[0].name)
        assertEquals(listOf("Mango"), found[0].songs.map { it.title })

        assertTrue(GlyphWheel.sections(library, GlyphSongSort(), query = "nothing like it").isEmpty())
    }

    @Test
    fun theWheelKeepsItsPlaceAcrossARebuild() {
        val sections = GlyphWheel.sections(library, GlyphSongSort(GlyphSortKey.PACK))
        val open = GlyphWheel.items(sections, open = "Alpha")
        val closed = GlyphWheel.items(sections, open = null)

        // The focused song, where it still is.
        assertEquals(3, GlyphWheel.anchor(open, "song:Cherry", "Alpha"))
        // Its folder, once the folder has closed under it.
        assertEquals(1, GlyphWheel.anchor(closed, "song:Cherry", "Alpha"))
        // The first item when nothing was focused.
        assertEquals(0, GlyphWheel.anchor(closed, null, null))
        assertEquals(-1, GlyphWheel.indexOf(closed, "song:Apple"))
    }

    @Test
    fun keysNameTheirTrack() {
        assertEquals("abc", GlyphWheel.trackIdOf(GlyphWheel.songKey("abc")))
        assertNull(GlyphWheel.trackIdOf(GlyphWheel.folderKey("abc")))
        assertNull(GlyphWheel.trackIdOf(null))
    }

    @Test
    fun packIsTheDefaultSort() {
        assertEquals(GlyphSortKey.PACK, GlyphSongSort().key)
        assertEquals(GlyphSortKey.PACK, GlyphSortKey.fromId("nonsense"))
    }

    // ── Groove Radar ────────────────────────────────────────────────────

    private fun note(
        beat: Float,
        timing: GlyphTiming,
        lane: GlyphLane = GlyphLane.LEFT,
        type: GlyphNoteType = GlyphNoteType.TAP,
        division: GlyphBeatDivision = GlyphBeatDivision.QUARTER,
        endBeat: Float = beat,
    ) = GlyphNote(
        lane = lane,
        type = type,
        beat = beat,
        timeSeconds = timing.beatToSeconds(beat),
        endTimeSeconds = timing.beatToSeconds(endBeat),
        division = division,
        measure = (beat / 4f).toInt(),
    )

    private fun chart(notes: List<GlyphNote>) = GlyphChart(
        difficulty = StepManiaDifficulty.HARD,
        meter = 9,
        chartName = "Test",
        stepsType = "dance-single",
        notes = notes.sortedBy { it.timeSeconds },
    )

    private val steady = GlyphTiming.constant(bpm = 120f)

    @Test
    fun quarterNotesAreStreamAndNothingElse() {
        // Sixteen quarters at 120 BPM: 0 s to 7.5 s.
        val stats = GlyphChartStats.of(chart((0 until 16).map { note(it.toFloat(), steady) }), steady)
        assertEquals(7.5f, stats.seconds, 1e-4f)
        assertEquals(16f / 7.5f / 7f, stats.stream, 1e-4f)
        // Eight beats are four seconds here: two notes a second, against ten.
        assertEquals(0.2f, stats.voltage, 1e-4f)
        assertEquals(0f, stats.air, 0f)
        assertEquals(0f, stats.freeze, 0f)
        assertEquals(0f, stats.chaos, 0f)
        // The graph holds every note: notes a second times each slice's length.
        assertEquals(16f, stats.density.sum() * stats.seconds / GlyphChartStats.BINS, 1e-3f)
        assertEquals(GlyphChartStats.BINS, stats.density.size)
    }

    @Test
    fun jumpsAreAirAndHoldsAreFreeze() {
        val notes = listOf(
            note(0f, steady, GlyphLane.LEFT), note(0f, steady, GlyphLane.RIGHT),
            note(4f, steady, GlyphLane.LEFT), note(4f, steady, GlyphLane.RIGHT),
            // A hold from 4 s to 8 s: the chart runs to the end of its tail.
            note(8f, steady, GlyphLane.DOWN, GlyphNoteType.HOLD, endBeat = 16f),
        )
        val stats = GlyphChartStats.of(chart(notes), steady)
        assertEquals(8f, stats.seconds, 1e-4f)
        assertEquals(2f / 8f, stats.air, 1e-4f)
        assertEquals(1f / 8f / 0.25f, stats.freeze, 1e-4f)
    }

    @Test
    fun offGridNotesAreChaosAndMinesCountForNothing() {
        val notes = (0 until 16).map { note(it.toFloat(), steady) } +
            note(1f / 3f, steady, GlyphLane.UP, division = GlyphBeatDivision.TWELFTH) +
            note(2f, steady, GlyphLane.RIGHT, GlyphNoteType.MINE)
        val stats = GlyphChartStats.of(chart(notes), steady)
        assertEquals(17, stats.notes)
        assertEquals(1f / 7.5f / 0.5f, stats.chaos, 1e-4f)
    }

    @Test
    fun voltageIsCountedInRealTime() {
        // 120 BPM for eight beats, then 240: the second eight beats last two
        // seconds, not four, so the same eight notes are four a second.
        val timing = GlyphTiming(
            offsetSeconds = 0f,
            segments = listOf(GlyphTiming.BpmSegment(0f, 120f), GlyphTiming.BpmSegment(8f, 240f)),
        )
        val stats = GlyphChartStats.of(chart((0 until 16).map { note(it.toFloat(), timing) }), timing)
        assertEquals(0.4f, stats.voltage, 1e-3f)
        assertEquals(120f..240f, timing.bpmRange())
    }

    @Test
    fun aChartWithNothingToHitIsFlat() {
        assertEquals(GlyphChartStats.EMPTY, GlyphChartStats.of(chart(emptyList()), steady))
        val mines = listOf(note(0f, steady, type = GlyphNoteType.MINE))
        assertEquals(GlyphChartStats.EMPTY, GlyphChartStats.of(chart(mines), steady))
    }
}
