// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the phone says when a run of charts ends, for a player long gone from the screen. */
class ChartNotificationTest {

    @Test
    fun oneSongReadsAsItAlwaysDid() {
        val made = StepManiaGenerationWorker.finishedMessage(listOf(ChartOutcome("a", "Song A", backendName = "x", savedToPack = true)))
        assertEquals("Chart ready" to "Song A is ready to play, and saved to your Trypt Mania pack.".replace("Trypt Mania", tf.monochrome.android.glyph.pack.StepManiaPack.OWN_PACK), made)
        val failed = StepManiaGenerationWorker.finishedMessage(listOf(ChartOutcome("a", "Song A", failure = "no server")))
        assertEquals("Chart not made" to "Song A: no server", failed)
    }

    @Test
    fun aBatchCountsAndNamesWhatFailed() {
        val outcomes = listOf(
            ChartOutcome("a", "Song A", backendName = "x"),
            ChartOutcome("b", "Song B", failure = "timeout"),
            ChartOutcome("c", "Song C", backendName = "x", packFailure = "full"),
            ChartOutcome("d", "Song D", failure = "bad file"),
        )
        val (heading, text) = StepManiaGenerationWorker.finishedMessage(outcomes)!!
        assertEquals("2 of 4 charts ready", heading)
        assertEquals("Not made: Song B: timeout, and 1 more. 1 could not be saved to your pack folder.", text)
    }

    @Test
    fun aCleanBatchSaysSo() {
        val outcomes = List(3) { ChartOutcome("$it", "Song $it", backendName = "x") }
        assertEquals("3 charts ready" to "All 3 songs are ready to play.", StepManiaGenerationWorker.finishedMessage(outcomes))
        assertNull(StepManiaGenerationWorker.finishedMessage(emptyList()))
    }
}
