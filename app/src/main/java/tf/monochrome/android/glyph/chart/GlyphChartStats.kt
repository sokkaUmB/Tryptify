// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import kotlin.math.roundToInt

/**
 * What a chart asks of the player, at a glance: DDR's Groove Radar and a
 * density graph.
 *
 * The radar's five values are StepMania's (its `RadarCategory`), each 0 to 1,
 * each a rate over the chart's length against the rate StepMania counts as
 * "really high":
 *
 * * **Stream** — notes a second, against 7.
 * * **Voltage** — notes a second in the busiest eight beats, against 10.
 * * **Air** — jumps (two arrows at once) a second, against 1.
 * * **Freeze** — holds and rolls a second, against 0.25.
 * * **Chaos** — notes off the quarter-and-eighth grid (twelfths, sixteenths
 *   and finer) a second, against 0.5.
 *
 * The density graph is notes a second across the chart in [BINS] slices — the
 * graph ITGmania's themes draw beside the music wheel.
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
    /** Notes a second in each of [BINS] equal slices of the chart. */
    val density: List<Float>,
    val peakNps: Float,
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

        private const val STREAM_HIGH = 7f
        private const val VOLTAGE_HIGH = 10f
        private const val AIR_HIGH = 1f
        private const val FREEZE_HIGH = 0.25f
        private const val CHAOS_HIGH = 0.5f
        private const val VOLTAGE_BEATS = 8f

        fun of(chart: GlyphChart, timing: GlyphTiming): GlyphChartStats {
            val notes = chart.scorableNotes
            if (notes.isEmpty()) return EMPTY
            val start = notes.first().timeSeconds
            val end = notes.maxOf { it.endTimeSeconds }
            // At least a second, so a chart of three notes is not "really high".
            val seconds = (end - start).coerceAtLeast(1f)

            // A row is every note on one beat: two or more of them is a jump.
            val rows = notes.groupBy { (it.beat * ROW_RESOLUTION).roundToInt() }
            val jumps = rows.values.count { row -> row.map { it.lane }.distinct().size >= 2 }
            val holds = notes.count { it.type.hasTail }
            val chaosRows = rows.values.count { row -> row.first().division.perBeat > 2 }

            // Voltage: the busiest eight beats, in notes a second of real time,
            // so a tempo change counts as the player feels it.
            val firstBeat = notes.first().beat
            val windows = notes.groupBy { ((it.beat - firstBeat) / VOLTAGE_BEATS).toInt() }
            val peakWindow = windows.maxOf { (index, inWindow) ->
                val from = firstBeat + index * VOLTAGE_BEATS
                val length = (timing.beatToSeconds(from + VOLTAGE_BEATS) - timing.beatToSeconds(from)).coerceAtLeast(0.25f)
                inWindow.size / length
            }

            val counts = IntArray(BINS)
            for (note in notes) {
                val bin = (((note.timeSeconds - start) / seconds) * BINS).toInt().coerceIn(0, BINS - 1)
                counts[bin] += 1
            }
            val binSeconds = seconds / BINS
            val density = counts.map { it / binSeconds }

            fun rate(count: Int, high: Float) = (count / seconds / high).coerceIn(0f, 1f)
            return GlyphChartStats(
                stream = rate(notes.size, STREAM_HIGH),
                voltage = (peakWindow / VOLTAGE_HIGH).coerceIn(0f, 1f),
                air = rate(jumps, AIR_HIGH),
                freeze = rate(holds, FREEZE_HIGH),
                chaos = rate(chaosRows, CHAOS_HIGH),
                density = density,
                peakNps = density.max(),
                averageNps = notes.size / seconds,
                notes = notes.size,
                seconds = seconds,
            )
        }

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
