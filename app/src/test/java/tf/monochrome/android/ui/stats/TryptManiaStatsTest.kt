package tf.monochrome.android.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.data.achievements.FeatureLog
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphStepGrade

class TryptManiaStatsTest {

    private var clock = 0L

    private fun run(
        chart: String = "a",
        percent: Float = 0.9f,
        grade: GlyphStepGrade? = GlyphStepGrade.TIER07,
        combo: GlyphFullCombo? = null,
        failed: Boolean = false,
        segment: Float? = null,
        score: Int = 1000,
        title: String = "Song $chart",
    ) = GlyphAttempt(
        id = "r${clock}", chartId = chart, songTitle = title, difficulty = StepManiaDifficulty.HARD,
        playedAtEpochMs = ++clock, score = score, accuracy = percent, maxCombo = 10,
        segmentStartSeconds = segment, percent = percent, stepGrade = grade?.name, fullCombo = combo?.name, failed = failed,
    )

    @Test
    fun `no runs is empty`() {
        assertEquals(TryptManiaStats.EMPTY, TryptManiaStats.from(emptyList()))
        assertEquals(TryptManiaStats.EMPTY, TryptManiaStats.from(listOf(run(segment = 10f))))
    }

    @Test
    fun `training segments are practice, not songs`() {
        val s = TryptManiaStats.from(listOf(run(), run(segment = 5f, combo = GlyphFullCombo.FLAWLESS, grade = GlyphStepGrade.TIER01)))
        assertEquals(1, s.runs)
        assertEquals(0, s.fullCombos)
        assertEquals(GlyphStepGrade.TIER07, s.bestGrade)
    }

    @Test
    fun `the best grade is by grade, never by score`() {
        // The AA run scored less: score grows with the combo, grade does not.
        val s = TryptManiaStats.from(listOf(run(grade = GlyphStepGrade.TIER10, score = 900_000), run(grade = GlyphStepGrade.TIER04, score = 10)))
        assertEquals(GlyphStepGrade.TIER04, s.bestGrade)
        assertTrue(s.reachedAa)
    }

    @Test
    fun `a failed run gives no grade`() {
        val s = TryptManiaStats.from(listOf(run(grade = GlyphStepGrade.TIER01, failed = true), run(grade = GlyphStepGrade.FAILED)))
        assertNull(s.bestGrade)
        assertFalse(s.reachedAa)
        assertEquals(2, s.runs)
    }

    @Test
    fun `runs from before percent was recorded are played but not scored`() {
        val s = TryptManiaStats.from(listOf(run(percent = -1f, grade = null)))
        assertEquals(1, s.runs)
        assertNull(s.recentPercent)
        assertNull(s.bestGrade)
    }

    @Test
    fun `charts, combos and the favourite`() {
        val s = TryptManiaStats.from(
            listOf(run("a"), run("b", combo = GlyphFullCombo.GREAT), run("b", combo = GlyphFullCombo.PERFECT), run("c")),
        )
        assertEquals(4, s.runs)
        assertEquals(3, s.charts)
        assertEquals(2, s.fullCombos)
        assertEquals(GlyphFullCombo.PERFECT, s.bestFullCombo)
        assertEquals("Song b", s.mostPlayedTitle)
        assertEquals(2, s.mostPlayedRuns)
        assertEquals(clock, s.lastPlayedAt)
    }

    @Test
    fun `accuracy is the latest ten, against the ten before`() {
        val older = List(10) { run(percent = 0.80f) }
        val newer = List(10) { run(percent = 0.90f) }
        val s = TryptManiaStats.from(older + newer)
        assertEquals(0.90f, s.recentPercent!!, 0.0001f)
        assertEquals(0.10f, s.trend!!, 0.0001f)
        assertNull(TryptManiaStats.from(newer).trend)
    }

    @Test
    fun `the Trypt Mania achievements`() {
        val runs = List(25) { run("c$it") } + run("x", combo = GlyphFullCombo.GREAT, grade = GlyphStepGrade.TIER04, percent = 0.97f)
        val facts = AchievementFacts(0, 0, 0, 0, FeatureLog(), TryptManiaStats.from(runs))
        val states = Achievements.evaluate(facts, emptyList(), emptyList(), now = 5L).associateBy { it.achievement }
        assertTrue(states.getValue(Achievement.TM_FULL_COMBO).unlocked)
        assertTrue(states.getValue(Achievement.TM_DOUBLE_A).unlocked)
        assertTrue(states.getValue(Achievement.TM_CHART_HOPPER).unlocked)

        val few = AchievementFacts(0, 0, 0, 0, FeatureLog(), TryptManiaStats.from(List(3) { run("d$it") }))
        val partial = Achievements.evaluate(few, emptyList(), emptyList(), now = 5L).associateBy { it.achievement }
        assertFalse(partial.getValue(Achievement.TM_FULL_COMBO).unlocked)
        assertFalse(partial.getValue(Achievement.TM_DOUBLE_A).unlocked)
        assertEquals(3, partial.getValue(Achievement.TM_CHART_HOPPER).done)
        assertEquals(25, partial.getValue(Achievement.TM_CHART_HOPPER).total)
    }
}
