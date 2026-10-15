// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import kotlin.math.roundToInt

/**
 * What a chart asks of the player, at a glance: DDR's Groove Radar and a
 * density graph.
 *
 * The radar's five values are StepMania's, as its `CalculateRadarValues`
 * computes them, each a rate over the song's length:
 *
 * * **Stream** — notes a second, over 7.
 * * **Voltage** — the most notes in any eight beats (a rolling window), as
 *   notes a beat, times the song's average beats a second, over 10.
 * * **Air** — jumps (rows of two or more arrows) a second.
 * * **Freeze** — holds a second. Rolls are not freezes.
 * * **Chaos** — rows off the quarter-and-eighth grid (twelfths, sixteenths
 *   and finer) a second, times 0.5. Every row counts, a mine's or a fake's
 *   too, as in StepMania.
 *
 * Each is drawn up to 1, where StepMania's own radar stops. The song's length
 * is the music's, as StepMania's is; without one, the span of the steps.
 *
 * The density graph is rows (a jump counts once) a second across the chart in
 * [BINS] slices, and the peak is the busiest measure's, the way ITGmania's
 * themes show it beside the music wheel.
 *
 * All of it is read from the chart's own steps — each one's time, beat and
 * beat division, whether it is a tap or a hold — the same per-step facts
 * difficulty research reads charts by (Franks et al., "Ordinal Regression for
 * Difficulty Estimation of StepMania Levels", 2023). Nothing here predicts a
 * meter: the chart's own meter is the difficulty number shown.
 */
data class GlyphChartStats(
    val stream: Float,
    val voltage: Float,
    val air: Float,
    val freeze: Float,
    val chaos: Float,
    /** Rows a second in each of [BINS] equal slices of the chart. */
    val density: List<Float>,
    /** Rows a second in the busiest measure. */
    val peakNps: Float,
    /** Notes a second over the song. */
    val averageNps: Float,
    val notes: Int,
    val seconds: Float,
) {
    /** The radar in DDR's order round the pentagon, clockwise from the top. */
    val radar: List<Float> get() = listOf(stream, chaos, freeze, air, voltage)

    companion object {
        const val BINS = 32

        /** The radar's axes, in [radar]'s order. */
        val AXES = listOf("STREAM", "CHAOS", "FREEZE", "AIR", "VOLTAGE")

        val EMPTY = GlyphChartStats(0f, 0f, 0f, 0f, 0f, List(BINS) { 0f }, 0f, 0f, 0, 0f)

        private const val STREAM_DIVISOR = 7f
        private const val VOLTAGE_DIVISOR = 10f
        private const val CHAOS_SCALE = 0.5f
        private const val VOLTAGE_BEATS = 8f
        private const val MEASURE_BEATS = 4f
        /** A measure shorter than this is a gimmick, not a peak (ITGmania's floor). */
        private const val SHORTEST_MEASURE_SECONDS = 0.12f

        /**
         * The stats of [chart] under [timing]. [songSeconds] is the music's
         * length; zero or less falls back to the span of the steps.
         */
        fun of(chart: GlyphChart, timing: GlyphTiming, songSeconds: Float = 0f): GlyphChartStats {
            val notes = chart.scorableNotes
            if (notes.isEmpty()) return EMPTY
            val start = notes.first().timeSeconds
            val end = notes.maxOf { it.endTimeSeconds }
            // The span of the steps, at least a second: the graph's width.
            val span = (end - start).coerceAtLeast(1f)
            val seconds = if (songSeconds > 0f) songSeconds else span

            // A row is every note on one beat: two or more of them is a jump.
            val rows = notes.groupBy { rowOf(it.beat) }
            val jumps = rows.values.count { row -> row.map { it.lane }.distinct().size >= 2 }
            val holds = notes.count { it.type == GlyphNoteType.HOLD }
            // Chaos counts every row, whatever its notes are.
            val chaosRows = chart.notes.groupBy { rowOf(it.beat) }.values
                .count { row -> row.first().division.perBeat > 2 }

            // Voltage: the most notes in any eight beats, a window rolling from
            // note to note, as StepMania's recent_notes.
            val beats = notes.map { it.beat }.sorted()
            var from = 0
            var busiest = 0
            for (to in beats.indices) {
                while (beats[from] < beats[to] - VOLTAGE_BEATS) from++
                busiest = maxOf(busiest, to - from + 1)
            }
            // StepMania spaces the beats evenly here, at the song's average.
            val averageBps = chart.notes.maxOf { it.beat } / seconds
            val voltage = busiest / VOLTAGE_BEATS * averageBps / VOLTAGE_DIVISOR

            val rowTimes = rows.values.map { row -> row.first().timeSeconds }
            val counts = IntArray(BINS)
            for (time in rowTimes) {
                val bin = (((time - start) / span) * BINS).toInt().coerceIn(0, BINS - 1)
                counts[bin] += 1
            }
            val binSeconds = span / BINS
            val density = counts.map { it / binSeconds }

            // The busiest measure, in rows a second of real time.
            val peak = rows.keys.groupBy { (it / (ROW_RESOLUTION * MEASURE_BEATS)).toInt() }
                .maxOfOrNull { (measure, inMeasure) ->
                    val first = measure * MEASURE_BEATS
                    val length = timing.beatToSeconds(first + MEASURE_BEATS) - timing.beatToSeconds(first)
                    if (length < SHORTEST_MEASURE_SECONDS) 0f else inMeasure.size / length
                } ?: 0f

            fun rate(count: Int) = count / seconds
            return GlyphChartStats(
                stream = (rate(notes.size) / STREAM_DIVISOR).coerceIn(0f, 1f),
                voltage = voltage.coerceIn(0f, 1f),
                air = rate(jumps).coerceIn(0f, 1f),
                freeze = rate(holds).coerceIn(0f, 1f),
                chaos = (rate(chaosRows) * CHAOS_SCALE).coerceIn(0f, 1f),
                density = density,
                peakNps = peak,
                averageNps = notes.size / seconds,
                notes = notes.size,
                seconds = seconds,
            )
        }

        private fun rowOf(beat: Float): Int = (beat * ROW_RESOLUTION).roundToInt()

        /** Rows a beat when grouping notes into rows: fine enough for 192nds. */
        private const val ROW_RESOLUTION = 48f
    }
}

/** The lowest and highest tempo a song's timing reaches. */
fun GlyphTiming.bpmRange(): ClosedFloatingPointRange<Float> {
    val values = segments.map { it.bpm }.filter { it > 0f }
    if (values.isEmpty()) return startBpm..startBpm
    return values.min()..values.max()
}
