package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Nothing reaches the DAC louder than its level, whatever the samples hold.
 *
 * The packers clamp after the gain, so a value far past full scale (16-bit
 * samples read as float, a filter blowing up) still came out at full scale:
 * a burst at a song change, at any volume. Bounded before the gain, the DAC
 * gets at most 6 dB over the level set.
 */
class SafeSampleTest {

    private val garbage = listOf(1e30f, -1e30f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 3.4e38f)

    @Test
    fun `garbage in a crossfade tail used to reach the DAC at full scale`() {
        // -14 dB, the level in the log: a tail of 1e30 rounded to the top.
        val unguarded = int16ToSubslotSample(0, 0.2f, 1e30f * 0.2f, 3)
        assertEquals(8_388_607, unguarded)
    }

    @Test
    fun `a tail is held to 6 dB over its gain whatever it holds`() {
        val gain = 0.2f
        for (t in garbage) {
            val out = int16ToSubslotSample(0, gain, safeSourceSample(t) * gain, 3)
            assertTrue("$t gave $out", abs(out) <= SOURCE_HEADROOM * gain * 8_388_607f + 1)
            val outFloat = floatToSubslotSample(0f * gain + safeSourceSample(t) * gain, 3)
            assertTrue("$t gave $outFloat", abs(outFloat) <= SOURCE_HEADROOM * gain * 8_388_607f + 1)
        }
        assertEquals(0f, safeSourceSample(Float.NaN), 0f)
    }

    @Test
    fun `a float stream keeps 6 dB of room and no more`() {
        val gain = 0.2f
        for (x in garbage) {
            val out = floatToSubslotSample(safeSourceSample(x) * gain, 3)
            assertTrue("$x gave $out", abs(out) <= 2 * gain * 8_388_607f + 1)
        }
        // Real audio, an EQ boost included, is untouched.
        for (x in listOf(0f, 0.5f, -1f, 1.4f, -1.9f)) assertEquals(x, safeSourceSample(x), 0f)
    }

    @Test
    fun `an EQ boosted tail is not clipped at full scale before its level`() {
        // The tail runs its own copy of the DSP chain. A +3.5 dB peak at half
        // volume fits the DAC; held at full scale first, it came out flattened
        // at 0.5 through every crossfade.
        val tailGain = 0.5f
        val boosted = 1.5f
        assertEquals(
            floatToSubslotSample(0.75f, 3),
            floatToSubslotSample(safeSourceSample(boosted) * tailGain, 3),
        )
        assertEquals(
            Math.round(0.75f * 8_388_607f),
            int16ToSubslotSample(0, 1f, safeSourceSample(boosted) * tailGain, 3),
        )
        for (t in listOf(0f, 0.3f, -0.99f, 1f, 1.4f, -1.9f)) assertEquals(t, safeSourceSample(t), 0f)
    }

    @Test
    fun `what is not audio is told apart`() {
        assertTrue(looksLikeAudio(0.9f, SOURCE_HEADROOM))
        assertTrue(looksLikeAudio(1.6f, SOURCE_HEADROOM))
        assertTrue(looksLikeAudio(-1.9f, SOURCE_HEADROOM))
        for (x in garbage) assertFalse("$x", looksLikeAudio(x, SOURCE_HEADROOM))
    }
}
