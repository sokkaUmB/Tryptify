// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.multiplayer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rooms server's wire format as the phone writes and reads it, and the clock it keeps against the server's. */
class RoomProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theHelloCarriesTheAccountsToken() {
        val hello = json.parseToJsonElement(RoomProtocol.hello("Felix", "tryptify-android", "eyJ.token")).jsonObject
        assertEquals("hello", hello.getValue("t").jsonPrimitive.content)
        assertEquals("Felix", hello.getValue("name").jsonPrimitive.content)
        assertEquals("eyJ.token", hello.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun theWelcomeSaysWhoTheServerTookUsFor() {
        val signedIn = RoomProtocol.parse(json, """{"t":"welcome","id":"p7","serverTime":1000,"version":"0.1.0","user":"uuid-1"}""")
        assertEquals(RoomMsg.Welcome("p7", 1000L, "0.1.0", "uuid-1"), signedIn)
        val guest = RoomProtocol.parse(json, """{"t":"welcome","id":"p8","serverTime":1000,"version":"0.1.0","user":null}""")
        assertEquals(RoomMsg.Welcome("p8", 1000L, "0.1.0", null), guest)
    }

    @Test
    fun framesComeAsPairsAndTheRoomAsItsView() {
        val frames = RoomProtocol.parse(
            json,
            """{"t":"frames","players":[["p1",{"score":1200,"combo":12,"acc":0.95,"hp":0.8,"pos":5000}],["p2",{"score":300}]]}""",
        ) as RoomMsg.Frames
        assertEquals(listOf("p1" to RoomLive(1200, 12, 0.95, 0.8, 5000), "p2" to RoomLive(score = 300)), frames.players)

        val room = RoomProtocol.parse(
            json,
            """{"t":"room","room":{"code":"AB2C","name":"Felix's room","hostId":"p1","state":"playing","startAt":99,
               "chart":{"hash":"h","title":"Song","artist":"Band","difficulty":"Hard","duration":180.5,"bpm":140},
               "players":[{"id":"p1","name":"Felix","ready":true,"hasSong":true},{"id":"p2","name":"Sam","spectating":true}]}}""",
        ) as RoomMsg.Room
        assertTrue(room.room.playing)
        assertEquals("AB2C", room.room.code)
        assertEquals("Hard", room.room.chart?.difficulty)
        assertEquals(listOf(true, null), room.room.players.map { it.hasSong })
        assertEquals("Sam", RoomProtocol.nameOf(room.room, "p2"))
    }

    @Test
    fun anUnknownOrBrokenMessageIsNotACrash() {
        assertEquals(RoomMsg.Ignored, RoomProtocol.parse(json, """{"t":"someday"}"""))
        assertEquals(RoomMsg.Ignored, RoomProtocol.parse(json, "not json"))
        assertTrue(RoomProtocol.parse(json, """{"t":"room","room":{"code":1}}""") is RoomMsg.Error)
    }

    @Test
    fun theChartHashIgnoresLineEndingsAndTrailingSpace() {
        val unix = "#TITLE:Song;\n#NOTES:\n0000\n1000\n;\n"
        val windows = "#TITLE:Song;\r\n#NOTES:\r\n0000\r\n1000\r\n;\r\n\r\n"
        assertEquals(RoomProtocol.chartHash(unix), RoomProtocol.chartHash(windows))
        assertEquals(64, RoomProtocol.chartHash(unix).length)
        assertNotEquals(RoomProtocol.chartHash(unix), RoomProtocol.chartHash(unix.replace("1000", "0100")))
    }

    @Test
    fun theClockTakesTheQuickestPongAndMapsTheServersMomentToOurs() {
        val clock = ClockSync()
        assertNull(clock.offsetMs)
        // Our clock reads 1_000 ms behind the server's; a slow pong first, a quick one after.
        clock.sample(clientMs = 10_000, serverMs = 11_400, receivedMs = 10_800)
        clock.sample(clientMs = 12_000, serverMs = 13_050, receivedMs = 12_100)
        assertEquals(100L, clock.rttMs)
        assertEquals(1_000L, clock.offsetMs)
        // The server says "start at 20_000": that is 19_000 on this phone.
        assertEquals(19_000L, clock.localMsFor(20_000))
    }

    @Test
    fun theSocketAddressIsTheServersUnderWs() {
        assertEquals("wss://rooms.example.app/secret/ws", RoomClient.wsUrl("https://rooms.example.app/secret/"))
        assertEquals("ws://10.0.0.2:8080/ws", RoomClient.wsUrl("http://10.0.0.2:8080"))
    }
}
