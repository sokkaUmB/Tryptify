package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.multiplayer.RoomFinished
import tf.monochrome.android.glyph.multiplayer.RoomLive
import tf.monochrome.android.glyph.multiplayer.RoomPlayer
import tf.monochrome.android.ui.glyph.roomBoardRows

/** The room's live board during play: who is where, and who stays on it. */
class GlyphRoomBoardTest {

    private fun player(id: String, score: Long, combo: Int = 0, hp: Double = 1.0, failed: Boolean = false) = RoomPlayer(
        id = id,
        name = id.uppercase(),
        live = RoomLive(score = score, combo = combo, hp = hp),
        finished = if (failed) RoomFinished(failed = true) else null,
    )

    @Test
    fun theBoardIsBestFirstWithEveryonesPlace() {
        val rows = roomBoardRows(listOf(player("a", 100), player("b", 300), player("c", 200)), myId = "a", maxRows = 8)
        assertEquals(listOf("b", "c", "a"), rows.map { it.id })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
        assertTrue(rows.last().isMe)
    }

    @Test
    fun overtakingMovesACardUp() {
        val before = roomBoardRows(listOf(player("me", 100), player("them", 200)), myId = "me", maxRows = 8)
        val after = roomBoardRows(listOf(player("me", 250), player("them", 200)), myId = "me", maxRows = 8)
        assertEquals(2, before.first { it.isMe }.rank)
        assertEquals(1, after.first { it.isMe }.rank)
        assertEquals("me", after.first().id)
    }

    @Test
    fun aPhoneOffTheTopIsPinnedLastWithItsRealPlace() {
        val room = (1..10).map { player("p$it", score = 1000L - it) } // p1 best … p10 worst
        val rows = roomBoardRows(room, myId = "p9", maxRows = 5)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p9"), rows.map { it.id })
        assertEquals(9, rows.last().rank)
        // Inside the top, nothing is pinned: the top five as they are.
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), roomBoardRows(room, myId = "p2", maxRows = 5).map { it.id })
    }

    @Test
    fun aFailedRunRanksUnderEveryLiveOne() {
        val rows = roomBoardRows(
            listOf(player("dead", 900, hp = 0.0), player("quit", 800, failed = true), player("alive", 10)),
            myId = null,
            maxRows = 8,
        )
        assertEquals(listOf("alive", "dead", "quit"), rows.map { it.id })
        assertTrue(rows[1].failed && rows[2].failed)
    }

    @Test
    fun aTieKeepsTheRoomsOrderSoCardsDoNotFlicker() {
        val rows = roomBoardRows(listOf(player("x", 500), player("y", 500), player("z", 500)), myId = null, maxRows = 8)
        assertEquals(listOf("x", "y", "z"), rows.map { it.id })
        assertTrue(roomBoardRows(emptyList(), myId = null, maxRows = 5).isEmpty())
    }
}
