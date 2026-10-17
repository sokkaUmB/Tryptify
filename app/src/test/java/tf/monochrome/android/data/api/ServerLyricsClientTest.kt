// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a TryptMania Parser server's lyrics answer, and reading it back from the cache. */
class ServerLyricsClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val answer = """{"success":true,"data":{"service":"tryptmania-parser","engine":"faster-whisper",
        "model":"small","device":"cpu","mode":"aligned","coverage":0.92,"language":"en","durationSeconds":200.5,
        "lines":[
          {"start":10.0,"end":11.9,"estimated":false,"words":[
            {"text":"Hello ","start":10.0,"end":10.4},{"text":"darkness, ","start":10.4,"end":10.8},{"text":"my ","start":10.8,"end":11.2},
            {"text":"old ","start":11.2,"end":11.6},{"text":"friend","start":11.6,"end":11.9}]},
          {"start":14.7,"end":16.98,"estimated":true,"words":[{"text":"Because ","start":14.7,"end":15.24},{"text":"a ","start":15.24,"end":15.42}]},
          {"start":20.0,"end":20.0,"estimated":false,"words":[]}
        ],
        "lrc":"[00:10.00]<00:10.00>Hello <00:10.40>darkness, <00:10.80>my <00:11.20>old <00:11.60>friend<00:11.90>\n"}}"""

    @Test
    fun linesComeBackWordTimedInMilliseconds() {
        val result = ServerLyricsClient.parse(json, answer, "parser.example.app", convertToRomaji = false)
        assertEquals("aligned", result.mode)
        assertEquals(0.92, result.coverage!!, 1e-9)
        assertEquals("en", result.language)
        assertEquals("small on parser.example.app", result.label)
        val lyrics = result.lyrics
        assertTrue(lyrics.isSynced)
        // The line with no words is not a line.
        assertEquals(2, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertEquals(10_000L, first.timeMs)
        assertEquals("Hello darkness, my old friend", first.text)
        assertEquals(listOf("Hello", "darkness,", "my", "old", "friend"), first.words.map { it.text })
        assertEquals(10_400L, first.words[1].startMs)
        assertEquals(10_800L, first.words[1].endMs)
        assertEquals(14_700L, lyrics.lines[1].timeMs)
        // It counts as word-timed for the race's tiers.
        assertEquals(3, tf.monochrome.android.data.repository.LyricsRace.tier(lyrics))
        assertTrue(result.lrc!!.startsWith("[00:10.00]"))
    }

    @Test
    fun aGpuServerIsSaidToBeOne() {
        val gpu = answer.replace("\"device\":\"cpu\"", "\"device\":\"cuda\"").replace("\"model\":\"small\"", "\"model\":\"large-v3\"")
        assertEquals("large-v3 on gpu.example.app (GPU)", ServerLyricsClient.parse(json, gpu, "gpu.example.app", false).label)
    }

    @Test
    fun theCacheReadsBackWhatWasKept() {
        val result = ServerLyricsClient.parse(json, answer, "parser.example.app", convertToRomaji = false)
        val back = ServerLyricsClient.fromCached(json, result.dataJson, convertToRomaji = false)!!
        assertEquals(result.lyrics, back.lyrics)
        assertEquals(result.label, back.label)
        assertEquals(result.mode, back.mode)
        assertNull(ServerLyricsClient.fromCached(json, "not json", false))
        assertNull(ServerLyricsClient.fromCached(json, """{"lines":[]}""", false))
    }

    @Test
    fun unspacedScriptsKeepTheirLinesUnspaced() {
        val ja = """{"success":true,"data":{"mode":"aligned","lines":[{"start":1.0,"end":2.5,"words":[
            {"text":"夜","start":1.0,"end":1.3},{"text":"に","start":1.3,"end":1.6},{"text":"駆","start":1.6,"end":1.9},
            {"text":"け","start":1.9,"end":2.2},{"text":"る","start":2.2,"end":2.5}]}]}}"""
        val lyrics = ServerLyricsClient.parse(json, ja, "h", false).lyrics
        assertEquals("夜に駆ける", lyrics.lines[0].text)
        assertEquals(5, lyrics.lines[0].words.size)
    }

    @Test
    fun theServersOwnReasonIsTheError() {
        val error = assertThrows(IOException::class.java) {
            ServerLyricsClient.parse(json, """{"success":false,"error":"no singing was heard"}""", "h", false)
        }
        assertEquals("The server could not time the lyrics: no singing was heard", error.message)
        assertThrows(IOException::class.java) { ServerLyricsClient.parse(json, "<html>", "h", false) }
        assertThrows(IOException::class.java) { ServerLyricsClient.parse(json, """{"success":true,"data":{"lines":[]}}""", "h", false) }
    }

    @Test
    fun theHostIsReadFromTheUrl() {
        assertEquals("parser.example.app", ServerLyricsClient.hostOf("https://parser.example.app/s3cret"))
    }
}
