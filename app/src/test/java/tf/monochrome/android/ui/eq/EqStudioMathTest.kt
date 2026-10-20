package tf.monochrome.android.ui.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

/**
 * The arithmetic of Precision AutoEQ's filter editor: what its scrub pills do
 * to a filter, and how its values read.
 */
class EqStudioMathTest {

    @Test
    fun `a filter keeps its colour, whatever is removed beside it`() {
        val bands = listOf(EqBand(id = 0, freq = 100f, gain = 0f), EqBand(id = 5, freq = 1000f, gain = 0f))
        assertEquals(listOf(5), StudioBands.without(bands, 0).map { it.id })
        assertNotEquals(studioFilterColor(0), studioFilterColor(1))
        assertEquals(studioFilterColor(5), studioFilterColor(5))
    }

    @Test
    fun `frequencies round to three significant figures`() {
        assertEquals(1230f, StudioBands.roundFreq(1234.5f), 1e-3f)
        assertEquals(98.8f, StudioBands.roundFreq(98.76f), 1e-3f)
        assertEquals(15800f, StudioBands.roundFreq(15834f), 1e-2f)
    }

    @Test
    fun `scrubbing moves in octaves and stops at the ends of the range`() {
        assertEquals(2000f, StudioBands.scrubFreq(1000f, 1f), 1e-2f)
        assertEquals(500f, StudioBands.scrubFreq(1000f, -1f), 1e-2f)
        assertEquals(20000f, StudioBands.scrubFreq(15000f, 5f), 0f)
        assertEquals(20f, StudioBands.scrubFreq(25f, -5f), 0f)
        assertEquals(2f, StudioBands.scrubQ(1f, 1f), 1e-5f)
        assertEquals(StudioBands.MAX_Q, StudioBands.scrubQ(8f, 4f), 0f)
        assertEquals(EqLimits.AUTOEQ_MAX_BAND_DB, StudioBands.clampGain(99f), 0f)
    }

    @Test
    fun `values read the way the export writes them`() {
        assertEquals("105 Hz", formatStudioFreq(105f))
        assertEquals("1.2 kHz", formatStudioFreq(1234f))
        assertEquals("16 kHz", formatStudioFreq(16000f))
        assertEquals("+3.5 dB", formatStudioGain(3.46f))
        assertEquals("−2.0 dB", formatStudioGain(-2f))
        assertEquals("0.0 dB", formatStudioGain(-0.01f))
        assertEquals("PK", FilterType.PEAKING.studioCode())
        assertEquals("LSC", FilterType.LOWSHELF.studioCode())
        assertEquals("HSC", FilterType.HIGHSHELF.studioCode())
    }
}
