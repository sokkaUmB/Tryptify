// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Find album art" takes a cover only from the same song; no sure match is no cover. */
class ArtMatchTest {
    private fun c(title: String, artist: String, seconds: Int, url: String = "https://x/$title-$artist-$seconds.jpg") =
        ArtMatch.Candidate(title, artist, seconds * 1000L, url)

    private val key = ArtMatch.Want("There Is A Key", "2 Brothers On The 4th Floor", 279_000)

    @Test
    fun theSameSongByTheSameArtistMatches() {
        val found = listOf(
            c("There Is A Key (Radio Edit)", "2 Brothers On The 4th Floor", 221),
            c("There Is A Key", "2 Brothers On The 4th Floor", 280),
            c("Never Alone", "2 Brothers On The 4th Floor", 279),
        )
        assertEquals(280_000L, ArtMatch.best(key, found)?.durationMs)
    }

    @Test
    fun anotherArtistsSongOfTheSameNameDoesNot() {
        assertNull(ArtMatch.best(key, listOf(c("There Is A Key", "Somebody Else", 279))))
    }

    @Test
    fun aDifferentLengthIsAnotherRecording() {
        assertNull(ArtMatch.best(key, listOf(c("There Is A Key", "2 Brothers On The 4th Floor", 300))))
    }

    @Test
    fun withNoArtistOnlyTheLengthCanVouch() {
        val anon = ArtMatch.Want("There Is A Key", "Various Artists", 279_000)
        assertEquals("Anyone", ArtMatch.best(anon, listOf(c("There Is A Key", "Anyone", 280)))?.artist)
        assertNull(ArtMatch.best(anon, listOf(c("There Is A Key", "Anyone", 290))))
        // Unknown length too: nothing to go on.
        assertNull(ArtMatch.best(anon.copy(durationMs = 0), listOf(c("There Is A Key", "Anyone", 280))))
    }

    @Test
    fun theSearchLeavesOutAnArtistThatNamesNobody() {
        assertEquals("There Is A Key", ArtMatch.query(ArtMatch.Want("There Is A Key (Remastered)", "Unknown Artist", 0)))
        assertEquals("2 Brothers On The 4th Floor There Is A Key", ArtMatch.query(key))
    }
}
