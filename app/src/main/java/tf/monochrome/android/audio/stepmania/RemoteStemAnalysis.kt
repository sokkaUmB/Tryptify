// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tf.monochrome.android.data.api.ApiServers
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.preferences.PreferencesManager

/**
 * A chart's separation done on a TryptMania Parser server
 * (github.com/tryptz/TryptMania-Parser), on a GPU where it has one.
 *
 * The song file goes up as it is; what comes back is not stems but the
 * envelopes the chart generator would have built from them — drums and each
 * [StemLayer], 100 values a second — made by the server from the same
 * arithmetic as [TransientEnvelopeBuilder] (held to it by
 * EnvelopeParityTest). A few hundred kilobytes instead of six stems of audio,
 * so the network costs about what uploading the song does.
 *
 * Any failure is the caller's to fall back from: [StepManiaConversionService]
 * separates on the phone instead.
 */
@Singleton
class RemoteStemAnalysis @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val json: Json,
) {

    /** The first server under Settings › Connections that separates stems, or null. */
    suspend fun server(): String? =
        ApiServers.serverFor(preferences.apiServers.first(), ApiService.STEMS)?.url

    suspend fun analyze(server: String, source: Uri): RemoteEnvelopes {
        val length = sizeOf(source)
        val response = httpClient.post("$server/api/stems/analyze") {
            // The server says nothing until the song is done: minutes on a CPU
            // host, seconds on a warm GPU, plus a cold start when it slept.
            timeout {
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType = ContentType.Application.OctetStream
                override val contentLength: Long? = length?.takeIf { it > 0 }
                override fun readFrom(): ByteReadChannel =
                    (context.contentResolver.openInputStream(source) ?: throw IOException("Could not open the song"))
                        .toByteReadChannel()
            })
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess() && !body.trimStart().startsWith("{")) {
            throw IOException("The server answered HTTP ${response.status.value}")
        }
        return parse(json, body, hostOf(server))
    }

    private fun sizeOf(source: Uri): Long? = runCatching {
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TIMEOUT_MS = 10 * 60 * 1000L

        fun hostOf(server: String): String = server.substringAfter("://").substringBefore('/')

        /**
         * A server's answer as the chart generator's builders. Throws with the
         * server's own message when it reports a failure.
         */
        fun parse(json: Json, body: String, host: String): RemoteEnvelopes {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("The server did not answer with JSON") }
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
                val message = root["error"]?.jsonPrimitive?.contentOrNull ?: "no reason given"
                throw IOException("The server could not separate it: $message")
            }
            val data = root["data"]?.jsonObject ?: throw IOException("The server's answer has no data")
            val sampleRate = data["sampleRate"]?.jsonPrimitive?.intOrNull ?: throw IOException("No sample rate")
            val frames = data["frames"]?.jsonPrimitive?.longOrNull ?: throw IOException("No length")
            val envelopes = data["envelopes"]?.jsonObject ?: throw IOException("No envelopes")

            fun builder(name: String): TransientEnvelopeBuilder? {
                val entry = envelopes[name] as? JsonObject ?: return null
                val raw = floats(entry["raw"]?.jsonPrimitive?.contentOrNull ?: return null)
                val rates = floats(entry["rates"]?.jsonPrimitive?.contentOrNull ?: return null)
                return TransientEnvelopeBuilder.restored(sampleRate, raw, rates, frames)
            }

            val drums = builder("drums") ?: throw IOException("No drum envelope")
            val layers = StemLayerEnvelopes(
                sampleRate,
                StemLayer.entries.associateWith { layer ->
                    builder(layer.name.lowercase())
                        // A layer the server did not send is silence, as one
                        // a separator never returned is on the phone.
                        ?: TransientEnvelopeBuilder.restored(sampleRate, FloatArray(drums.raw().size), FloatArray(drums.raw().size), frames)
                },
            )
            val model = data["model"]?.jsonPrimitive?.contentOrNull ?: "a server"
            val device = data["device"]?.jsonPrimitive?.contentOrNull
            val label = buildString {
                append(model)
                append(" on ")
                append(host)
                if (device == "cuda") append(" (GPU)")
            }
            return RemoteEnvelopes(drums, layers, label)
        }

        private fun floats(base64: String): FloatArray {
            val bytes = Base64.getDecoder().decode(base64)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}

/** What a server sent back, ready for [StemRhythmAnalyzer], and who made it. */
class RemoteEnvelopes(
    val drums: TransientEnvelopeBuilder,
    val layers: StemLayerEnvelopes,
    val label: String,
)
