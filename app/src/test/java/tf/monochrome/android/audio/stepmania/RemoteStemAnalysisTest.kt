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
import tf.monochrome.android.data.api.ApiServer
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
            "sampleRate":44100,"frames":220500,"envelopeHz":100,"pricePerHour":0.8,"idleSeconds":300,
            "seconds":{"upload":0.5,"decode":0.2,"separate":0.9,"envelopes":0.1},"envelopes":{$envelopes}}}"""
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

    @Test
    fun anAnswerSaysHowLongItTookAndWhatItCosts() {
        val run = RemoteStemAnalysis.parse(json, answer("cuda", listOf("bass")), "host").run
        assertEquals("cuda", run.device)
        assertEquals(5.0, run.audioSeconds, 1e-9)
        assertEquals(1.0, run.separateSeconds, 1e-9)
        assertEquals(0.8, run.pricePerHour!!, 1e-9)
        assertEquals(300, run.idleSeconds)
        // Separating (1.0 s) outlasts getting it there (0.7 s): 1.0 s for 5 s of audio.
        assertEquals(0.2, run.batchSecondsPerAudioSecond!!, 1e-9)
    }

    private fun server(url: String, device: String?) =
        ApiServer(url, setOf(ApiService.STEMS), stemDevice = device)

    @Test
    fun fastTriesTheGpuThenTheCpuAndSlowNeverTheGpu() {
        val cpu = server("https://railway", "cpu")
        val gpu = server("https://modal", "cuda")
        val unknown = server("https://old", null)
        val servers = listOf(cpu, unknown, gpu)
        assertEquals(listOf(gpu, unknown, cpu), RemoteStemAnalysis.planFor(servers, ChartMode.FAST))
        // An unknown one may be a GPU: Slow must not bill one.
        assertEquals(listOf(cpu), RemoteStemAnalysis.planFor(servers, ChartMode.SLOW))
        assertEquals(emptyList<ApiServer>(), RemoteStemAnalysis.planFor(listOf(gpu), ChartMode.SLOW))
    }

    @Test
    fun theProbeReadsWhatAServerSeparatesOn() {
        val info = """{"success":true,"data":{"service":"tryptmania-parser","device":"cpu","model":"htdemucs_6s_onnx"}}"""
        assertEquals("cpu", ApiServerProber.deviceOf(info, json))
        assertEquals(null, ApiServerProber.deviceOf("<html>", json))
        assertEquals(false, server("u", "cpu").separatesOnGpu)
        assertEquals(true, server("u", "cuda").separatesOnGpu)
        assertEquals(null, server("u", null).separatesOnGpu)
    }
}
