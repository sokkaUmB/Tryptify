// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.ChartInFlight
import tf.monochrome.android.audio.stepmania.ChartJob
import tf.monochrome.android.audio.stepmania.ChartOutcome
import tf.monochrome.android.audio.stepmania.ChartQueueState

/** The chart panel, from the line: one song as it always read, a batch by its count. */
class GlyphGenerationStateTest {

    private fun job(id: String) = ChartJob(id, "file:///$id", "/c/$id.ssc", "Song $id", "Artist", "$id.mp3")

    @Test
    fun nothingInTheLineShowsNoPanel() {
        assertNull(generationOf(ChartQueueState()))
    }

    @Test
    fun oneSongShowsItsOwnStages() {
        val running = generationOf(ChartQueueState(running = listOf(ChartInFlight(job("a"), 0.4f, "Separating on the GPU"))))!!
        assertEquals("Separating on the GPU", running.stage)
        assertEquals(0.4f, running.fraction, 0f)
        assertFalse(running.isBatch)
        assertTrue(running.active)

        val done = generationOf(ChartQueueState(finished = listOf(ChartOutcome("a", "Song a", backendName = "htdemucs_ft on modal (GPU)"))))!!
        assertEquals(1f, done.fraction, 0f)
        assertEquals("htdemucs_ft on modal (GPU)", done.backendName)
        assertTrue(done.isFinished)

        val failed = generationOf(ChartQueueState(finished = listOf(ChartOutcome("a", "Song a", failure = "no server"))))!!
        assertEquals("no server", failed.failure)
    }

    @Test
    fun aBatchCountsAndShowsTheSongInFlight() {
        val state = ChartQueueState(
            waiting = listOf(job("c"), job("d")),
            running = listOf(ChartInFlight(job("b"), 0.5f, "Generating charts")),
            finished = listOf(ChartOutcome("a", "Song a", failure = "timeout")),
        )
        val panel = generationOf(state)!!
        assertTrue(panel.isBatch)
        assertEquals(4, panel.total)
        assertEquals(1, panel.done)
        assertEquals(1, panel.failed)
        assertEquals("Song b", panel.title)
        assertEquals("Song a: timeout", panel.firstFailure)
        assertEquals(1.5f / 4f, panel.fraction, 1e-6f)
    }

    @Test
    fun aFinishedBatchNamesItsServerOnlyWhenThereWasOne() {
        val one = generationOf(ChartQueueState(finished = List(2) { ChartOutcome("$it", "S", backendName = "gpu") }))!!
        assertEquals("gpu", one.backendName)
        val two = generationOf(ChartQueueState(finished = listOf(ChartOutcome("1", "S", backendName = "gpu"), ChartOutcome("2", "S", backendName = "cpu"))))!!
        assertNull(two.backendName)
    }
}
