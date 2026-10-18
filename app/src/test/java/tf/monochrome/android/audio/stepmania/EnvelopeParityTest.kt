// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

/**
 * The envelopes a TryptMania Parser server sends are the ones this app would
 * build itself: the server's port of [TransientEnvelopeBuilder] and the
 * builder agree on `envelope_parity.json`, which the server's repository
 * writes (tools/make_parity_fixture.py) and tests too. The signals are
 * integer arithmetic, built bit for bit the same on both sides.
 */
class EnvelopeParityTest {

    private val fixture = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("stemsapi/envelope_parity.json")!!.readText(),
    ).jsonObject
    private val sampleRate = fixture["sampleRate"]!!.jsonPrimitive.int
    private val frames = fixture["frames"]!!.jsonPrimitive.int

    /** make_parity_fixture.signal: a triangle wave, an LCG's noise, a burst every half second. */
    private fun signal(seed: Long, period: Int): FloatArray {
        var x = seed
        return FloatArray(frames) { i ->
            x = (1103515245L * x + 12345L) % 2147483648L
            val noise = (x % 65536L).toDouble() / 65536.0 - 0.5
            val triangle = abs(2 * (i % period) - period).toDouble() / period - 0.5
            val gain = if ((i / 4410) % 5 == 0) 4.0 else 1.0
            (0.4 * triangle + 0.2 * noise * gain).toFloat()
        }
    }

    private fun expected(case: String, key: String): FloatArray =
        fixture[case]!!.jsonObject[key]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    private fun assertMatches(case: String, builder: TransientEnvelopeBuilder) {
        val raw = builder.raw()
        val rates = builder.crossingRates()
        val expectedRaw = expected(case, "raw")
        val expectedRates = expected(case, "rates")
        assertEquals("$case hops", expectedRaw.size, raw.size)
        for (i in raw.indices) {
            assertTrue("$case raw[$i] ${raw[i]} vs ${expectedRaw[i]}", abs(raw[i] - expectedRaw[i]) < 1e-5f)
            assertEquals("$case rates[$i]", expectedRates[i], rates[i], 1e-3f)
        }
    }

    @Test
    fun theBuilderMatchesTheServerStreamedInPieces() {
        val left = signal(1, 100)
        val right = signal(2, 147)
        val builder = TransientEnvelopeBuilder(sampleRate)
        // Uneven pieces, as a song streams through the phone's own pipeline.
        for ((from, until) in listOf(0 to 10_000, 10_000 to 77_777, 77_777 to frames)) {
            builder.add(left, right, from, until)
        }
        assertMatches("stereo", builder)
    }

    @Test
    fun aLayerOfSeveralStemsMatchesTheServer() {
        val stems = mapOf(
            Stem.OTHER to SampleEdits.Buffer(signal(3, 211), signal(4, 89), sampleRate),
            Stem.PIANO to SampleEdits.Buffer(signal(5, 64), signal(6, 330), sampleRate),
        )
        val layers = StemLayerEnvelopes(sampleRate)
        layers.add(stems, frames, 0, frames)
        assertMatches("melody", layers.builders.getValue(StemLayer.MELODY))
    }

    @Test
    fun aRestoredBuilderGivesBackWhatItWasGiven() {
        val raw = expected("stereo", "raw")
        val rates = expected("stereo", "rates")
        val restored = TransientEnvelopeBuilder.restored(sampleRate, raw, rates, frames.toLong())
        assertTrue(raw.contentEquals(restored.raw()))
        assertTrue(rates.contentEquals(restored.crossingRates()))
        assertEquals(frames.toLong(), restored.frames)
    }
}
