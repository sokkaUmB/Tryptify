package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** File names by their place on a release give their title without the numbering. */
class TrackNamesTest {

    @Test
    fun `track and disc numbering comes off`() {
        for (name in listOf(
            "01 - Song", "1-05 - Song", "1-05 Song", "2-05 Song", "05. Song", "05 Song",
            "12 - Song", "12. Song", "CD1 - 05 - Song", "cd 2 - 03 - Song", "Disc 2 - 03 - Song",
            "Disc 2 03 Song", "CD1 - Song",
        )) {
            assertEquals(name, "Song", TrackNames.withoutNumberPrefix(name))
        }
    }

    @Test
    fun `a title that starts with a number keeps it`() {
        for (name in listOf("99 Luftballons", "7 Rings", "1999", "22", "4 Minutes", "Song 2", "Artist - Song")) {
            assertEquals(name, name, TrackNames.withoutNumberPrefix(name))
        }
    }

    @Test
    fun `numbering then an artist and title leaves the artist and title`() {
        assertEquals("Dolly Parton - Jolene", TrackNames.withoutNumberPrefix("05 - Dolly Parton - Jolene"))
        assertEquals("Dolly Parton" to "Jolene", splitArtistTitle(TrackNames.withoutNumberPrefix("05 - Dolly Parton - Jolene")))
    }

    @Test
    fun `a name that is only numbering stays as it is`() {
        assertEquals("01", TrackNames.withoutNumberPrefix("01"))
    }

    @Test
    fun `numbering an older scan took for an artist is recognised`() {
        for (artist in listOf("01", "1-05", "CD1", "Disc 2", "12")) assertTrue(artist, TrackNames.looksLikeNumbering(artist))
        for (artist in listOf("Dolly Parton", "Blink-182", "2Pac", "The 1975", null)) {
            assertFalse("$artist", TrackNames.looksLikeNumbering(artist))
        }
    }
}
