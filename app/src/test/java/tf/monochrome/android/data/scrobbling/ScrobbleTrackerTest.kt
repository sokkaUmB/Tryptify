package tf.monochrome.android.data.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.Track

class ScrobbleTrackerTest {
    private fun song(seconds: Int) = Track(id = 1, title = "Roundabout", duration = seconds)

    @Test
    fun halfTheSongHeardIsAScrobbleStampedWithItsStart() {
        val t = ScrobbleTracker()
        t.start(song(200), nowUnix = 1_000)
        t.heard(99_000)
        assertNull(t.due())
        t.heard(1_000)
        assertEquals(song(200) to 1_000L, t.due())
        // Once a play.
        t.heard(100_000)
        assertNull(t.due())
    }

    @Test
    fun aLongSongScrobblesAtFourMinutes() {
        val t = ScrobbleTracker()
        t.start(song(1_200), nowUnix = 5)
        t.heard(239_000)
        assertNull(t.due())
        t.heard(1_000)
        assertEquals(5L, t.due()?.second)
    }

    @Test
    fun aShortSongNeverAndAFasterSpeedSooner() {
        val short = ScrobbleTracker()
        short.start(song(25), nowUnix = 0)
        short.heard(25_000)
        assertNull(short.due())
        // At 2x, 50 s of a 200 s song is half of it heard.
        val fast = ScrobbleTracker()
        fast.start(song(200), nowUnix = 0)
        fast.heard(50_000, speed = 2f)
        assertEquals(0L, fast.due()?.second)
    }

    @Test
    fun aNewPlayStartsItsOwnCount() {
        val t = ScrobbleTracker()
        t.start(song(200), nowUnix = 0)
        t.heard(90_000)
        t.start(song(200), nowUnix = 300)
        t.heard(20_000)
        assertNull(t.due())
    }
}
