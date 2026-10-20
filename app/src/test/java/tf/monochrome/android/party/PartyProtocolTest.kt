// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * A listening party's wire format as the phone reads and writes it, held to
 * what the rooms server's party rules (tryptz/tryptify-party, `cpp/party.cpp`) send; and
 * the clock and the song match the phone follows the party with.
 */
class PartyProtocolTest {

    // As the app's own (AppModule).
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    /** A party as the server writes it: a song playing, one uploading in Up next, a vote in. */
    private val party = """{"t":"party","party":{"code":"K7QD","name":"Friday night","hostId":"p1",""" +
        """"members":[{"id":"p1","name":"Maya","user":"u-1","guest":false,"web":false,"away":false},""" +
        """{"id":"p2","name":"Sam","user":null,"guest":true,"web":true,"away":false},""" +
        """{"id":"p3","name":"Jonas","user":"u-3","guest":false,"web":false,"away":true}],""" +
        """"now":{"id":"s1","title":"Air for Life","artist":"Above & Beyond","album":"","durationMs":352000,""" +
        """"addedBy":"p3","addedByName":"Jonas","isrc":"GBX","catalogId":"tidal:1","audioHash":null,"cover":null,""" +
        """"shared":{"ready":true,"bytes":100,"received":100,"ext":"flac"}},""" +
        """"queue":[{"id":"s2","title":"Tone","artist":"Emmanuel Top","album":"","durationMs":300000,"addedBy":"p1",""" +
        """"addedByName":"Maya","isrc":null,"catalogId":null,"audioHash":null,"cover":null,""" +
        """"shared":{"ready":false,"bytes":200,"received":124,"ext":""}}],""" +
        """"playback":{"itemId":"s1","positionMs":134000,"atServerMs":1700000000000,"paused":false},""" +
        """"skip":{"votes":["p2"],"needed":1},"sharedBytes":100,"sharedLimit":1073741824}}"""

    @Test
    fun thePartyReadsAsTheServerWritesIt() {
        val view = (PartyProtocol.parse(json, party) as PartyMsg.Party).party
        assertEquals("K7QD", view.code)
        assertEquals("Maya", view.host?.name)
        // Jonas is away: two listening, one of them on the web.
        assertEquals(2, view.listening)
        assertEquals(1, view.onTheWeb)
        assertEquals("Jonas", view.now?.addedByName)
        assertTrue(view.now?.shared?.ready == true)
        assertEquals(0.62f, view.queue[0].shared!!.progress, 0.001f)
        assertEquals(listOf("p2"), view.skip.votes)
        assertNull(view.queue[0].isrc)
    }

    @Test
    fun theWelcomeSaysWhetherTheSeatWasKept() {
        assertEquals(
            PartyMsg.Welcome("p4", 1000L, "k".repeat(32), resumed = true),
            PartyProtocol.parse(json, """{"t":"welcome","id":"p4","serverTime":1000,"version":"0.1.0","user":null,"key":"${"k".repeat(32)}","resumed":true}"""),
        )
        // A server from before resuming does not say: not resumed.
        val old = PartyProtocol.parse(json, """{"t":"welcome","id":"p4","serverTime":1000,"key":"k"}""") as PartyMsg.Welcome
        assertFalse(old.resumed)
    }

    @Test
    fun theServersOwnLinesCarryTheirKind() {
        val history = PartyProtocol.parse(
            json,
            """{"t":"history","lines":[{"kind":"join","id":"p2","name":"Sam","text":"web","at":5},""" +
                """{"kind":"say","id":"p2","name":"Sam","text":"this drop though","at":6},""" +
                """{"kind":"skip","id":null,"name":"","text":"Air for Life","at":7},""" +
                """{"kind":"dance","id":null,"name":"","text":"","at":8}]}""",
        ) as PartyMsg.History
        // A kind this phone does not know is left out, not shown wrong.
        assertEquals(listOf(PartyLineKind.JOIN, PartyLineKind.SAY, PartyLineKind.SKIP), history.lines.map { it.kind })
        assertNull(history.lines[2].id)
        val added = PartyProtocol.parse(json, """{"t":"added","ref":"r1","itemId":"s9"}""")
        assertEquals(PartyMsg.Added("r1", "s9"), added)
        assertEquals(PartyMsg.Joined("K7QD", "tok"), PartyProtocol.parse(json, """{"t":"joined","code":"K7QD","songToken":"tok"}"""))
        assertEquals(PartyMsg.Ignored, PartyProtocol.parse(json, "not json"))
    }

    @Test
    fun whatThePhoneSendsIsWhatTheServerReads() {
        val hello = json.parseToJsonElement(PartyProtocol.hello("Maya", "tryptify-android", "eyJ", resume = "key")).jsonObject
        assertEquals("key", hello.getValue("resume").jsonPrimitive.content)
        val create = json.parseToJsonElement(PartyProtocol.create("Friday")).jsonObject
        assertEquals("party", create.getValue("kind").jsonPrimitive.content)
        assertEquals("K7QD", json.parseToJsonElement(PartyProtocol.join(" k7qd ")).jsonObject.getValue("code").jsonPrimitive.content)
        val add = json.parseToJsonElement(
            PartyProtocol.add(PartyAdd("Tone", "Emmanuel Top", "", 300_000, isrc = "X", share = true), "r1"),
        ).jsonObject
        assertEquals("queueAdd", add.getValue("t").jsonPrimitive.content)
        assertEquals("r1", add.getValue("ref").jsonPrimitive.content)
        val item = add.getValue("item").jsonObject
        assertEquals("300000", item.getValue("durationMs").jsonPrimitive.content)
        assertEquals("true", item.getValue("share").jsonPrimitive.content)
        assertEquals("null", item.getValue("cover").toString())
        val vote = json.parseToJsonElement(PartyProtocol.skipVote("s1", false)).jsonObject
        assertEquals("s1", vote.getValue("itemId").jsonPrimitive.content)
        assertEquals("false", vote.getValue("vote").jsonPrimitive.content)
        assertEquals("60000", json.parseToJsonElement(PartyProtocol.seek(60_000)).jsonObject.getValue("positionMs").jsonPrimitive.content)
    }

    @Test
    fun songsAndInvitesLiveUnderTheServersAddress() {
        assertEquals(
            "https://rooms.example/s3cret/api/party/song?item=s1&ext=flac",
            PartyProtocol.songUrl("https://rooms.example/s3cret/", "s1", "flac"),
        )
        assertEquals("https://rooms.example/s3cret/api/party/song?item=s1", PartyProtocol.songUrl("https://rooms.example/s3cret", "s1"))
        assertEquals("https://rooms.example/s3cret/party/K7QD", PartyProtocol.inviteLink("https://rooms.example/s3cret", "k7qd"))
    }

    @Test
    fun aServerSaysWhetherItHoldsParties() {
        assertTrue(PartyProtocol.supportsParties(json, """{"success":true,"data":{"service":"tryptmania-multiplayer","features":["party"]}}"""))
        assertFalse(PartyProtocol.supportsParties(json, """{"success":true,"data":{"service":"tryptmania-multiplayer","rooms":0}}"""))
        assertFalse(PartyProtocol.supportsParties(json, "<html>"))
    }

    @Test
    fun theOneClockRunsOnUnlessPaused() {
        val playing = PartyPlayback("s1", positionMs = 10_000, atServerMs = 1_000, paused = false)
        assertEquals(14_000, playing.positionAt(5_000))
        // A server clock a little behind this phone's never runs the song backwards.
        assertEquals(10_000, playing.positionAt(500))
        val paused = playing.copy(paused = true)
        assertEquals(10_000, paused.positionAt(99_000))
        // Past the end is the end.
        assertEquals(12_000, PartySync.target(playing, 999_000, durationMs = 12_000))
    }

    @Test
    fun withinSixHundredMillisecondsIsInStep() {
        assertFalse(PartySync.needsSeek(localMs = 10_000, targetMs = 10_600))
        assertTrue(PartySync.needsSeek(localMs = 10_000, targetMs = 10_601))
        assertTrue(PartySync.needsSeek(localMs = 12_000, targetMs = 10_000))
    }

    @Test
    fun aDriftIsMovedOnlyOnceSeenTwiceAndNotWhileSettling() {
        val drift = PartyDrift(toleranceMs = 600, settleMs = 4_000, confirm = 2)
        drift.loaded(nowMs = 0)
        // Settling after the load: nothing moves, however far out.
        assertNull(drift.check(localMs = 0, targetMs = 5_000, nowMs = 1_000))
        // Out once: a blip, left alone. Out twice: moved, aiming ahead.
        assertNull(drift.check(localMs = 9_000, targetMs = 10_000, nowMs = 5_000))
        // Aimed where the party is now, plus what a seek costs.
        assertEquals(11_000L + 250, drift.check(localMs = 10_000, targetMs = 11_000, nowMs = 6_000))
        // Just seeked: settling again, so the next second does not seek again.
        assertNull(drift.check(localMs = 11_000, targetMs = 12_000, nowMs = 7_000))
    }

    @Test
    fun aHostsSeekIsFollowedAtOnce() {
        val drift = PartyDrift(toleranceMs = 600, settleMs = 4_000, confirm = 2)
        drift.loaded(nowMs = 0)
        drift.moved()
        // Still inside the settling time, and seen only once: followed anyway.
        assertEquals(40_000L + 250, drift.check(localMs = 12_000, targetMs = 40_000, nowMs = 1_000))
    }

    @Test
    fun aSeekLearnsWhatItCostsSoTheNextLandsInStep() {
        val drift = PartyDrift(toleranceMs = 600, settleMs = 1_000, confirm = 1)
        drift.loaded(nowMs = 0)
        assertEquals(20_250L, drift.check(localMs = 18_000, targetMs = 20_000, nowMs = 2_000))
        // It landed 400 ms behind: a seek costs that much more than was allowed for.
        assertNull(drift.check(localMs = 29_600, targetMs = 30_000, nowMs = 4_000))
        assertEquals(650L, drift.leadMs)
        // Inside the tolerance nothing moves, and the lead stays learnt.
        assertNull(drift.check(localMs = 40_100, targetMs = 40_000, nowMs = 6_000))
        assertEquals(650L, drift.leadMs)
    }

    private fun track(id: Long, title: String, artist: String, seconds: Int) =
        Track(id = id, title = title, duration = seconds, artist = Artist(id = 1, name = artist))

    private fun item(title: String, artist: String, ms: Long) = PartyItem(id = "s1", title = title, artist = artist, durationMs = ms)

    @Test
    fun aSongIsMatchedByTitleArtistAndLength() {
        val found = listOf(
            track(1, "Air for Life (Radio Edit)", "Above & Beyond", 210),
            track(2, "Air for Life", "Above & Beyond", 352),
            track(3, "Air for Life", "Someone Else", 352),
        )
        assertEquals(2L, PartyMatch.best(item("Air for Life", "Above & Beyond", 352_500), found)?.id)
        // Nothing near enough the length: no match rather than the wrong recording.
        assertNull(PartyMatch.best(item("Air for Life", "Above & Beyond", 400_000), found))
        assertNull(PartyMatch.best(item("Tone", "Emmanuel Top", 300_000), found))
    }

    @Test
    fun aSongIsNamedInItsCatalogue() {
        assertEquals("tidal:42", PartyMatch.catalogId(track(42, "T", "A", 1), localFile = false))
        assertEquals("deezer:9", PartyMatch.catalogId(track(42, "T", "A", 1).copy(deezerId = 9), localFile = false))
        assertNull(PartyMatch.catalogId(track(42, "T", "A", 1), localFile = true))
    }
}
