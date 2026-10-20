package tf.monochrome.android.ui.stats

import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphStepGrade

/**
 * Trypt Mania on the Stats screen: what the card shows and what its
 * achievements count, all time, from the saved runs.
 *
 * Only whole songs count; a Training Ground segment is practice. Runs are
 * compared by their percent (dance points) or grade, never by score: score
 * grows with the combo now, so an older run's score would read as worse
 * play. Runs from before percent was recorded count as played but not in
 * the accuracy or the grade.
 *
 * The store keeps the latest 60 runs of each chart (and every difficulty's
 * best), so [runs] is what is kept, which on a much-played chart is fewer
 * than were played.
 */
data class TryptManiaStats(
    /** Whole songs played. */
    val runs: Int,
    /** Different charts with at least one whole song played. */
    val charts: Int,
    /** Whole songs finished without dropping a step. */
    val fullCombos: Int,
    val bestFullCombo: GlyphFullCombo?,
    /** The best grade of any run that was not failed; null before one is recorded. */
    val bestGrade: GlyphStepGrade?,
    /** The mean percent (0..1) of the latest [RECENT] scored runs; null before one is recorded. */
    val recentPercent: Float?,
    /** Recent against the [RECENT] before them, in points of percent; null until there are two sets. */
    val trend: Float?,
    val mostPlayedTitle: String?,
    val mostPlayedRuns: Int,
    val lastPlayedAt: Long?,
) {
    /** Whether a run is good enough for the AA achievement: AA or better, not failed. */
    val reachedAa: Boolean get() = bestGrade != null && bestGrade.ordinal <= GlyphStepGrade.TIER04.ordinal

    companion object {
        const val RECENT = 10

        val EMPTY = TryptManiaStats(0, 0, 0, null, null, null, null, null, 0, null)

        fun from(attempts: List<GlyphAttempt>): TryptManiaStats {
            val whole = attempts.filter { it.isFullRun }.sortedByDescending { it.playedAtEpochMs }
            if (whole.isEmpty()) return EMPTY
            val combos = whole.mapNotNull { a -> GlyphFullCombo.entries.firstOrNull { it.name == a.fullCombo } }
            val grades = whole.filter { it.hasStepScore && !it.failed }.mapNotNull { GlyphStepGrade.fromName(it.stepGrade) }
                .filter { it != GlyphStepGrade.FAILED }
            val scored = whole.filter { it.hasStepScore }.map { it.percent.coerceIn(0f, 1f) }
            val recent = scored.take(RECENT)
            val before = scored.drop(RECENT).take(RECENT)
            val byChart = whole.groupBy { it.chartId }
            val favourite = byChart.maxWithOrNull(
                compareBy<Map.Entry<String, List<GlyphAttempt>>> { it.value.size }.thenBy { it.value.first().playedAtEpochMs },
            )
            return TryptManiaStats(
                runs = whole.size,
                charts = byChart.size,
                fullCombos = combos.size,
                bestFullCombo = combos.minByOrNull { it.ordinal },
                bestGrade = grades.minByOrNull { it.ordinal },
                recentPercent = recent.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                trend = if (recent.isNotEmpty() && before.isNotEmpty()) {
                    (recent.average() - before.average()).toFloat()
                } else {
                    null
                },
                mostPlayedTitle = favourite?.value?.first()?.songTitle,
                mostPlayedRuns = favourite?.value?.size ?: 0,
                lastPlayedAt = whole.first().playedAtEpochMs,
            )
        }
    }
}
