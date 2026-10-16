// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.api.ApiServerProber
import tf.monochrome.android.data.api.ApiService

/** Reading a TryptMania Parser server's answer, and finding the server at all. */
class RemoteStemAnalysisTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun b64(values: FloatArray): String {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun answer(device: String, layers: List<String>): String {
        val raw = FloatArray(500) { (it % 50) / 10f }
        val rates = FloatArray(500) { 440f }
        val envelopes = (listOf("drums") + layers).joinToString(",") {
            "\"$it\":{\"raw\":\"${b64(raw)}\",\"rates\":\"${b64(rates)}\"}"
        }
        return """{"success":true,"data":{"service":"tryptmania-parser","model":"htdemucs_ft","device":"$device",
            "sampleRate":44100,"frames":220500,"envelopeHz":100,"envelopes":{$envelopes}}}"""
    }

    @Test
    fun anAnswerBecomesTheChartGeneratorsBuilders() {
        val result = RemoteStemAnalysis.parse(json, answer("cuda", listOf("bass", "vocals", "melody")), "x.modal.run")
        assertEquals(500, result.drums.raw().size)
        assertEquals(220_500L, result.drums.frames)
        assertEquals(4.9f, result.drums.raw()[49], 1e-6f)
        assertEquals(440f, result.layers.builders.getValue(StemLayer.VOCALS).crossingRates()[0])
        assertEquals("htdemucs_ft on x.modal.run (GPU)", result.label)
        // Enough for the analyzer, which wants four seconds and more.
        assertTrue(StemRhythmAnalyzer().analyze(result.drums, result.layers).durationSeconds > 4f)
    }

    @Test
    fun aLayerNotSentIsSilence() {
        val result = RemoteStemAnalysis.parse(json, answer("cpu", listOf("bass")), "host")
        val vocals = result.layers.builders.getValue(StemLayer.VOCALS).raw()
        assertEquals(500, vocals.size)
        assertTrue(vocals.all { it == 0f })
        assertEquals("htdemucs_ft on host", result.label)
    }

    @Test
    fun theServersOwnReasonIsKept() {
        val failure = assertThrows(IOException::class.java) {
            RemoteStemAnalysis.parse(json, """{"success":false,"error":"could not read the audio: bad"}""", "host")
        }
        assertTrue(failure.message!!.contains("could not read the audio"))
        assertThrows(IOException::class.java) { RemoteStemAnalysis.parse(json, "<html>", "host") }
    }

    @Test
    fun theProbeFindsAParserServerAndNothingElse() {
        val info = """{"success":true,"data":{"service":"tryptmania-parser"}}"""
        assertEquals(null, ApiServerProber.verdict(ApiService.STEMS, 200, info, json))
        assertEquals("HTTP 404", ApiServerProber.verdict(ApiService.STEMS, 404, "{\"detail\":\"Not Found\"}", json))
    }

    @Test
    fun theHostIsWhatTheProgressNames() {
        assertEquals("abc--tryptmania-parser-web.modal.run", RemoteStemAnalysis.hostOf("https://abc--tryptmania-parser-web.modal.run/s3cret"))
    }
}
