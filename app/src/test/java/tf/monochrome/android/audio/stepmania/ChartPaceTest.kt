// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a batch's confirmation promises, from what the servers have shown. */
class ChartPaceTest {

    @Test
    fun aGpuBatchCostsItsTimeAndTheIdleAfter() {
        // An hour of music at 0.1 s a second: 6 minutes, plus a 20 s start.
        val estimate = ChartPace.estimate(3600, gpu = true, ChartPace.Pace(ratio = 0.1, pricePerHour = 0.80, idleSeconds = 300))
        assertEquals(380L, estimate.seconds)
        // (380 + 300) s of an L4 at $0.80 an hour.
        assertEquals(0.80 * 680 / 3600, estimate.costDollars!!, 1e-9)
    }

    @Test
    fun aServerThatDoesNotSayItsPriceShowsNoCost() {
        val estimate = ChartPace.estimate(600, gpu = false, ChartPace.Pace())
        assertEquals((600 * ChartPace.DEFAULT_CPU_RATIO).toLong() + ChartPace.SERVER_START_SECONDS, estimate.seconds)
        assertNull(estimate.costDollars)
    }

    @Test
    fun nothingLearnedYetUsesWhatWasMeasured() {
        val gpu = ChartPace.estimate(300, gpu = true, ChartPace.Pace())
        assertEquals((300 * ChartPace.DEFAULT_GPU_RATIO).toLong() + ChartPace.SERVER_START_SECONDS, gpu.seconds)
    }

    @Test
    fun copiesOfTheServerShareTheBatchAndEachBillsItsOwnStartAndIdle() {
        // Eight songs, an hour of music at 0.4 s a second on four CPU copies:
        // 24 minutes of separating shared four ways, plus a start.
        val pace = ChartPace.Pace(ratio = 0.4, pricePerHour = 0.22, idleSeconds = 60, parallel = 4)
        val estimate = ChartPace.estimate(3600, gpu = false, pace, songs = 8)
        assertEquals(1440L / 4 + ChartPace.SERVER_START_SECONDS, estimate.seconds)
        assertEquals(0.22 * (1440 + 4 * (ChartPace.SERVER_START_SECONDS + 60)) / 3600, estimate.costDollars!!, 1e-9)
    }

    @Test
    fun aBatchUsesNoMoreCopiesThanItHasSongs() {
        val pace = ChartPace.Pace(ratio = 0.4, parallel = 6)
        val two = ChartPace.estimate(600, gpu = false, pace, songs = 2)
        assertEquals(240L / 2 + ChartPace.SERVER_START_SECONDS, two.seconds)
    }

    @Test
    fun songsInFlightAreTheServersCopiesAndOneAhead() {
        assertEquals("one server, one sent ahead", 2, StepManiaGenerationWorker.lanes(null))
        assertEquals(2, StepManiaGenerationWorker.lanes(1))
        assertEquals("Modal's four CPU copies", 5, StepManiaGenerationWorker.lanes(4))
        assertEquals("Railway's six replicas", 7, StepManiaGenerationWorker.lanes(6))
        assertEquals(StepManiaGenerationWorker.MAX_LANES, StepManiaGenerationWorker.lanes(50))
    }

    @Test
    fun theLearnedPaceMovesTowardsEachSong() {
        assertEquals(0.2, ChartPace.blend(null, 0.2), 0.0)
        assertEquals(0.13, ChartPace.blend(0.1, 0.2), 1e-9)
    }

    @Test
    fun aServerRunCostsTheLongerOfGettingItThereAndSeparatingIt() {
        val slowUpload = RemoteRun("cuda", audioSeconds = 200.0, uploadSeconds = 30.0, decodeSeconds = 2.0, separateSeconds = 20.0, pricePerHour = null, idleSeconds = null)
        assertEquals(32.0 / 200.0, slowUpload.batchSecondsPerAudioSecond!!, 1e-9)
        val fastUpload = slowUpload.copy(uploadSeconds = 3.0)
        assertEquals(20.0 / 200.0, fastUpload.batchSecondsPerAudioSecond!!, 1e-9)
        assertNull(slowUpload.copy(audioSeconds = 0.0).batchSecondsPerAudioSecond)
    }

    @Test
    fun timesAndCostsReadAsWords() {
        assertEquals("under a minute", ChartPace.describeDuration(45))
        assertEquals("about a minute", ChartPace.describeDuration(80))
        assertEquals("about 6 minutes", ChartPace.describeDuration(380))
        assertEquals("about 1 h", ChartPace.describeDuration(3600))
        assertEquals("about 1 h 32 min", ChartPace.describeDuration(5520))
        assertEquals("under 1¢", ChartPace.describeCost(0.004))
        assertEquals("about 15¢", ChartPace.describeCost(0.151))
        assertEquals("about $1.40", ChartPace.describeCost(1.4))
    }
}
