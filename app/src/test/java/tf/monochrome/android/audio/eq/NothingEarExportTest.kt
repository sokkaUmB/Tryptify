package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs

/**
 * The Nothing Ear export: the share code has to be byte-for-byte what Nothing
 * X reads, and the 8-band fit has to stay inside the limits it was given while
 * still tracking the EQ it approximates.
 */
class NothingEarExportTest {

    /** "Custom 1", exported by Nothing X from an Ear (B171). */
    private val captured = "H4sIAAAAAAAA/2NIYACDGCcGhgZ7CPsOlK1wACjuDKQdGRgcgOw7zmfPnLGFqFnqAlfv4OcKVe/A4HDO9e" +
        "yZNQ5QcTeQOCOHc2lxSX6ugiEADU1TRmwAAAA="

    private val capturedBands = listOf(
        NothingEarShareCode.Band(55f, 0f, 1f), NothingEarShareCode.Band(110f, 0f, 1f),
        NothingEarShareCode.Band(220f, -2.5f, 10f), NothingEarShareCode.Band(440f, -3f, 0.1f),
        NothingEarShareCode.Band(1320f, 0f, 1f), NothingEarShareCode.Band(3300f, 0f, 1f),
        NothingEarShareCode.Band(6600f, 2.5f, 5.4f), NothingEarShareCode.Band(13200f, 0f, 1f),
    )

    @Test
    fun `decodes the captured export to the bands set in the app`() {
        val profile = NothingEarShareCode.decode(captured)
        assertEquals("Custom 1", profile.name)
        assertEquals(capturedBands, profile.bands)
    }

    @Test
    fun `encoding the captured bands gives back the app's exact code`() {
        assertEquals(captured, NothingEarShareCode.encode(NothingEarShareCode.Profile("Custom 1", capturedBands)))
    }

    @Test
    fun `a long name is cut to 255 bytes without splitting a character`() {
        val name = "é".repeat(200) // 400 bytes of UTF-8
        val back = NothingEarShareCode.decode(
            NothingEarShareCode.encode(NothingEarShareCode.Profile(name, capturedBands))
        )
        assertEquals("é".repeat(127), back.name)
    }

    /** An AutoEQ-shaped correction: shelves at the ends, bells between. */
    private val autoEqLike = listOf(
        EqBand(0, FilterType.LOWSHELF, 105f, 5.5f, 0.7f),
        EqBand(1, FilterType.PEAKING, 60f, -1.5f, 1.4f),
        EqBand(2, FilterType.PEAKING, 230f, -2.8f, 0.9f),
        EqBand(3, FilterType.PEAKING, 900f, 1.6f, 1.1f),
        EqBand(4, FilterType.PEAKING, 1900f, -2.2f, 2.6f),
        EqBand(5, FilterType.PEAKING, 3200f, 3.4f, 2.1f),
        EqBand(6, FilterType.PEAKING, 5200f, -4.1f, 3.5f),
        EqBand(7, FilterType.PEAKING, 6800f, 2.0f, 5.0f),
        EqBand(8, FilterType.PEAKING, 8900f, -3.0f, 2.8f),
        EqBand(9, FilterType.HIGHSHELF, 10000f, -1.5f, 0.7f),
    )

    @Test
    fun `fits a 10-band AutoEQ onto the earbuds' 8 bells closely`() {
        val fit = NothingEarFitter.fit(autoEqLike)
        assertEquals(8, fit.bands.size)
        assertTrue(fit.bands.all { it.type == FilterType.PEAKING && it.enabled })
        assertTrue("rms ${fit.rmsErrorDb} dB", fit.rmsErrorDb < 0.5)
    }

    @Test
    fun `every band stays inside the limits, band 1 in its own range`() {
        // Asks for more than ±6 dB anywhere, and its biggest bump is far
        // above anything band 1 can reach.
        val wants = listOf(
            EqBand(0, FilterType.PEAKING, 3000f, 10f, 1.5f),
            EqBand(1, FilterType.PEAKING, 300f, -9f, 1f),
        )
        for (limits in listOf(NothingEarFitter.APP_LIMITS, NothingEarFitter.EXTENDED_LIMITS)) {
            val fit = NothingEarFitter.fit(wants, limits)
            fit.bands.forEachIndexed { i, b ->
                assertTrue("band $i gain ${b.gain}", b.gain >= limits.minGainDb - 1e-4 && b.gain <= limits.maxGainDb + 1e-4)
                assertTrue("band $i q ${b.q}", b.q >= limits.minQ - 1e-4 && b.q <= limits.maxQ + 1e-4)
                assertTrue("band $i freq ${b.freq}", b.freq.toDouble() in limits.freqRange(i).let {
                    (it.start - 1e-3)..(it.endInclusive + 1e-3)
                })
            }
        }
    }

    @Test
    fun `the volume hack fits big moves the app's sliders cannot`() {
        // Seven ±9 dB bells. Within ±6 dB each needs two stacked bands, which
        // 8 bands cannot give all seven; past ±6 dB each needs one.
        val wants = listOf(60f, 250f, 700f, 1800f, 4000f, 8000f, 14000f).mapIndexed { i, f ->
            EqBand(i, FilterType.PEAKING, f, if (i % 2 == 0) 9f else -9f, 2f)
        }
        val app = NothingEarFitter.fit(wants, NothingEarFitter.APP_LIMITS)
        val hack = NothingEarFitter.fit(wants, NothingEarFitter.EXTENDED_LIMITS)
        assertTrue("hack rms ${hack.rmsErrorDb}", hack.rmsErrorDb < 0.05)
        assertTrue("app rms ${app.rmsErrorDb}", app.rmsErrorDb > 0.5)
    }

    @Test
    fun `a flat EQ exports flat bands`() {
        val fit = NothingEarFitter.fit(emptyList())
        assertTrue(fit.bands.all { abs(it.gain) < 0.01f })
        assertTrue(fit.rmsErrorDb < 1e-3)
    }
}
