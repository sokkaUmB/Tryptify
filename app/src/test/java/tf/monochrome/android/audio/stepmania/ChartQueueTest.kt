// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart line: what a batch is made of, and the one rule that keeps it
 * moving — a song added is never left without a worker to chart it.
 */
class ChartQueueTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val folder: File = Files.createTempDirectory("chart-queue").toFile()
    private val file = File(folder, "queue.json")

    private fun queue() = ChartQueue(file, json)

    private fun job(id: String, mode: ChartMode = ChartMode.FAST) =
        ChartJob(id, "file:///$id.mp3", "/charts/$id.ssc", "Song $id", "Artist", "$id.mp3", 180, mode)

    @Test
    fun songsJoinInOrderAndOnlyOnce() = runBlocking {
        val q = queue()
        assertEquals(2, q.add(listOf(job("a"), job("b"), job("a"))).count)
        assertEquals(1, q.add(listOf(job("b"), job("c"))).count)
        assertEquals(listOf("a", "b", "c"), q.state.value.waiting.map { it.trackId })
    }

    @Test
    fun theFirstSongStartsAWorkerAndTheRestJoinIt() = runBlocking {
        val q = queue()
        assertTrue(q.add(listOf(job("a"))).startWorker)
        assertFalse(q.add(listOf(job("b"))).startWorker)
        q.startDraining()
        assertEquals("a", q.next()?.trackId)
        q.finish(ChartOutcome("a", "Song a", backendName = "x"))
        assertEquals("b", q.next()?.trackId)
        q.finish(ChartOutcome("b", "Song b", backendName = "x"))
        // Empty: the worker may stop, and the next song starts another.
        assertNull(q.next())
        assertTrue(q.add(listOf(job("c"))).startWorker)
    }

    @Test
    fun aWorkerWaitsUnderASongStillInFlight() = runBlocking {
        val q = queue()
        q.add(listOf(job("a")))
        assertEquals("a", q.next()?.trackId)
        val waiting = async { q.next() }
        delay(50)
        assertFalse("must not stop while a song is in flight", waiting.isCompleted)
        q.add(listOf(job("b")))
        assertEquals("b", withTimeout(1_000) { waiting.await() }?.trackId)
    }

    @Test
    fun aFailureIsCountedAndTheBatchGoesOn() = runBlocking {
        val q = queue()
        q.add(listOf(job("a"), job("b")))
        q.next()
        q.finish(ChartOutcome("a", "Song a", failure = "no server"))
        assertEquals("b", q.next()?.trackId)
        q.finish(ChartOutcome("b", "Song b", backendName = "x"))
        val state = q.state.value
        assertEquals(2, state.total)
        assertEquals(1, state.failed)
        assertFalse(state.active)
        assertEquals(1f, state.fraction, 0f)
    }

    @Test
    fun aStoppedSongGoesBackToTheFront() = runBlocking {
        val q = queue()
        q.add(listOf(job("a"), job("b")))
        val a = q.next()!!
        q.putBack(a)
        assertEquals(listOf("a", "b"), q.state.value.waiting.map { it.trackId })
        assertTrue(q.state.value.running.isEmpty())
    }

    @Test
    fun cancellingDropsEverythingAndTheNextSongStartsAFreshWorker() = runBlocking {
        val q = queue()
        q.add(listOf(job("a"), job("b")))
        val a = q.next()!!
        q.cancelAll()
        // The worker on its way out finds nothing to put back or finish.
        q.putBack(a)
        q.finish(ChartOutcome("a", "Song a", backendName = "x"))
        assertEquals(0, q.state.value.total)
        assertTrue(q.add(listOf(job("c"))).startWorker)
    }

    @Test
    fun theLineOutlivesTheProcessWithSongsInFlightFirst() = runBlocking {
        val q = queue()
        q.add(listOf(job("a"), job("b", ChartMode.SLOW)))
        q.next()
        q.progress("a", 0.5f, "Separating on the GPU")

        val again = queue()
        again.ensureLoaded()
        val state = again.state.value
        assertEquals(listOf("a", "b"), state.waiting.map { it.trackId })
        assertEquals(ChartMode.SLOW, state.waiting[1].mode)
        assertTrue(state.running.isEmpty())
        // Left behind by a worker that died with the process: it gets one again, once.
        assertTrue(again.claimIfIdle())
        assertFalse(again.claimIfIdle())
    }

    @Test
    fun aSongChartedAgainIsCountedByHowItGoesThisTime() = runBlocking {
        val q = queue()
        q.add(listOf(job("a")))
        q.next()
        q.finish(ChartOutcome("a", "Song a", failure = "timeout"))
        q.add(listOf(job("a")))
        assertEquals(0, q.state.value.failed)
        assertEquals(1, q.state.value.total)
    }

    @Test
    fun progressCountsSongsInFlightByTheirShare() = runBlocking {
        val q = queue()
        q.add(listOf(job("a"), job("b"), job("c"), job("d")))
        q.next()
        q.finish(ChartOutcome("a", "Song a", backendName = "x"))
        q.next()
        q.progress("b", 0.5f, "Separating")
        assertEquals((1f + 0.5f) / 4f, q.state.value.fraction, 1e-6f)
        assertTrue(q.state.value.holds("b"))
        assertFalse(q.state.value.holds("a"))
    }

    @Test
    fun anEmptyLineLeavesNoFile() = runBlocking {
        val q = queue()
        q.add(listOf(job("a")))
        assertTrue(file.isFile)
        q.cancelAll()
        assertFalse(file.exists())
    }
}
