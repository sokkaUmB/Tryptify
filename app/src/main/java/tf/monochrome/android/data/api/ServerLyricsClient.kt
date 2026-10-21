// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter

/**
 * Lyrics timed on a TryptMania Parser server (github.com/tryptz/TryptMania-Parser):
 * the song file goes up, the server separates the vocals and hears them with
 * Whisper, and every word comes back with the time it is sung.
 *
 * Sent with the plain lyrics the app already found (any source, untimed or
 * line-timed), the server keeps those words and only adds the times
 * (`aligned`); sent alone, what it heard are the lyrics (`transcribed`).
 * Minutes on a CPU host, seconds on a GPU: never part of the lyrics race,
 * only [ServerLyricsEngine][tf.monochrome.android.data.repository.ServerLyricsEngine]'s
 * background job, whose answer is kept for good.
 */
@Singleton
class ServerLyricsClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val json: Json,
) {

    /** Every server under Settings › Connections that times lyrics, in the list's order. */
    val serverFlow: Flow<List<ApiServer>> =
        preferences.apiServers.map { servers -> servers.filter { ApiService.LYRICS in it.services } }

    suspend fun servers(): List<ApiServer> = serverFlow.first()

    suspend fun sync(
        server: String,
        source: Uri,
        query: LyricsQuery?,
        plain: String?,
        convertToRomaji: Boolean,
        language: String? = null,
        /** Called once the whole song is up: from then on the server is hearing it. */
        onUploaded: () -> Unit = {},
    ): ServerLyrics {
        val length = sizeOf(source)?.takeIf { it > 0 }
        var told = false
        val response = httpClient.post("$server/api/lyrics/sync") {
            // The server says nothing until the song is done: a separation
            // and a transcription, minutes on a CPU host, plus a cold start.
            timeout {
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
            onUpload { sent, total ->
                if (!told && total != null && sent >= total) {
                    told = true
                    onUploaded()
                }
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        plain?.takeIf { it.isNotBlank() }?.let { append("lyrics", it) }
                        query?.title?.takeIf { it.isNotBlank() }?.let { append("title", it) }
                        query?.artist?.takeIf { it.isNotBlank() }?.let { append("artist", it) }
                        language?.takeIf { it.isNotBlank() }?.let { append("language", it) }
                        append(
                            "audio",
                            ChannelProvider(length) {
                                (context.contentResolver.openInputStream(source) ?: throw IOException("Could not open the song"))
                                    .toByteReadChannel()
                            },
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                append(HttpHeaders.ContentDisposition, "filename=\"song\"")
                            },
                        )
                    },
                ),
            )
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess() && !body.trimStart().startsWith("{")) {
            throw IOException("The server answered HTTP ${response.status.value}")
        }
        return parse(json, body, hostOf(server), convertToRomaji)
    }

    private fun sizeOf(source: Uri): Long? = runCatching {
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TIMEOUT_MS = 20 * 60 * 1000L

        fun hostOf(server: String): String = server.substringAfter("://").substringBefore('/')

        /**
         * A server's answer as lyrics. Throws with the server's own message
         * when it reports a failure, or when its lines hold no words.
         */
        fun parse(json: Json, body: String, host: String, convertToRomaji: Boolean): ServerLyrics {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("The server did not answer with JSON") }
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
                val message = root["error"]?.jsonPrimitive?.contentOrNull ?: "no reason given"
                throw IOException("The server could not time the lyrics: $message")
            }
            val data = root["data"]?.jsonObject ?: throw IOException("The server's answer has no data")
            return fromData(data, host, convertToRomaji) ?: throw IOException("The server sent no lyrics")
        }

        /**
         * Lyrics made by hand, in the shape of a server's answer, so the
         * cache holds one kind of thing and [fromCached] reads both.
         */
        fun toData(lyrics: Lyrics, lrc: String?): String {
            val lines = JsonArray(
                lyrics.lines.map { line ->
                    val words = line.words.ifEmpty {
                        // A line with no words is one word for the whole line's time.
                        listOf(LyricWord(line.timeMs, line.timeMs, line.text))
                    }
                    JsonObject(
                        mapOf(
                            "start" to JsonPrimitive(line.timeMs / 1000.0),
                            "end" to JsonPrimitive(words.last().endMs / 1000.0),
                            "estimated" to JsonPrimitive(false),
                            "words" to JsonArray(
                                words.mapIndexed { i, w ->
                                    JsonObject(
                                        mapOf(
                                            "text" to JsonPrimitive(if (i < words.lastIndex) w.text + " " else w.text),
                                            "start" to JsonPrimitive(w.startMs / 1000.0),
                                            "end" to JsonPrimitive(w.endMs / 1000.0),
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                },
            )
            val data = mutableMapOf<String, JsonElement>(
                "mode" to JsonPrimitive("edited"),
                "model" to JsonPrimitive("the lyrics editor"),
                "lines" to lines,
            )
            lrc?.let { data["lrc"] = JsonPrimitive(it) }
            return JsonObject(data).toString()
        }

        /** An answer's `data`, as the cache keeps it, back as lyrics; null when it has no lines. */
        fun fromCached(json: Json, dataJson: String, convertToRomaji: Boolean): ServerLyrics? =
            runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull()
                ?.let { fromData(it, it["host"]?.jsonPrimitive?.contentOrNull.orEmpty(), convertToRomaji) }

        private fun fromData(data: JsonObject, host: String, convertToRomaji: Boolean): ServerLyrics? {
            val lines = (data["lines"] as? JsonArray)
                ?.mapNotNull { element -> (element as? JsonObject)?.let { lineOf(it, convertToRomaji) } }
                ?.takeIf { it.isNotEmpty() } ?: return null
            val model = data["model"]?.jsonPrimitive?.contentOrNull ?: "a server"
            val device = data["device"]?.jsonPrimitive?.contentOrNull
            val label = buildString {
                append(model)
                if (host.isNotEmpty()) append(" on ").append(host)
                if (device == "cuda") append(" (GPU)")
            }
            // The host rides along into the cache, so a kept answer still says where it came from.
            val kept = JsonObject(data + ("host" to JsonPrimitive(host)))
            return ServerLyrics(
                lyrics = Lyrics(lines = lines, isSynced = true),
                mode = data["mode"]?.jsonPrimitive?.contentOrNull ?: "aligned",
                coverage = data["coverage"]?.jsonPrimitive?.doubleOrNull,
                language = data["language"]?.jsonPrimitive?.contentOrNull,
                label = label,
                lrc = data["lrc"]?.jsonPrimitive?.contentOrNull,
                dataJson = kept.toString(),
            )
        }

        /** One line of the answer: its words in ms, its text as the words read; null when it has no words. */
        private fun lineOf(line: JsonObject, convertToRomaji: Boolean): LyricLine? {
            val words = (line["words"] as? JsonArray)?.mapNotNull { element ->
                val word = element as? JsonObject ?: return@mapNotNull null
                val raw = word["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: start
                raw to LyricWord(
                    startMs = (start * 1000).roundToLong(),
                    endMs = (end * 1000).roundToLong().coerceAtLeast((start * 1000).roundToLong()),
                    // The karaoke view spaces words itself; the server's space after a word goes.
                    text = raw.trim().let { if (convertToRomaji) RomajiConverter.convert(it) else it },
                )
            }?.filter { it.second.text.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
            // The line as written: the words with the server's own spacing,
            // which is none between the characters of an unspaced script.
            val text = words.joinToString("") { it.first }.trim().let { if (convertToRomaji) RomajiConverter.convert(it) else it }
            val start = line["start"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).roundToLong() } ?: words.first().second.startMs
            return LyricLine(timeMs = start, text = text, words = words.map { it.second })
        }
    }
}

/** What a server sent back, and where it came from. */
class ServerLyrics(
    val lyrics: Lyrics,
    /** `aligned` (the given words, timed) or `transcribed` (the words as heard). */
    val mode: String,
    /** The share of the given words heard where they were placed; null when none were given. */
    val coverage: Double?,
    val language: String?,
    val label: String,
    val lrc: String?,
    /** The answer's `data`, as the cache keeps it: [ServerLyricsClient.fromCached] reads it back. */
    val dataJson: String,
)
