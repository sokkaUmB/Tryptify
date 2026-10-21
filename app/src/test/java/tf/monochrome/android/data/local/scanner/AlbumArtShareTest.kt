// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A coverless song borrows a cover only from its own album in its own folder, never from a catch-all tag. */
class AlbumArtShareTest {
    private fun m(path: String, art: String?) = AlbumArtShare.Member(path, hasArt = art != null, art = art)

    @Test
    fun catchAllTagsAreNoAlbum() {
        for (tag in listOf(null, "", "  ", "Various Artists", "various artists ", "VA", "Unknown Album", "Singles", "Untitled")) {
            assertTrue("$tag", AlbumArtShare.isPlaceholder(tag))
        }
        assertFalse(AlbumArtShare.isPlaceholder("Now That's What I Call Music! 50"))
        assertFalse(AlbumArtShare.isPlaceholder("Discovery"))
    }

    @Test
    fun aCoverlessSongInACatchAllAlbumBorrowsNothing() {
        // The song in Music/Tryptify tagged "Various Artists", beside a
        // stranger elsewhere with a cover under the same tag.
        val covers = AlbumArtShare.coversByPath(
            listOf(m("/Music/Tryptify/There Is A Key.flac", null), m("/Music/Other/Stranger.mp3", "art-stranger")),
            placeholder = true,
        )
        assertNull(covers["/Music/Tryptify/There Is A Key.flac"])
    }

    @Test
    fun anAlbumsCoverReachesOnlyItsOwnFolder() {
        val covers = AlbumArtShare.coversByPath(
            listOf(
                m("/Music/Daft Punk/Discovery/01.flac", "art-a"),
                m("/Music/Daft Punk/Discovery/02.flac", null),
                // The same album title in another folder: another copy, its own cover or none.
                m("/Music/Tryptify/03.flac", null),
            ),
            placeholder = false,
        )
        assertEquals("art-a", covers["/Music/Daft Punk/Discovery/02.flac"])
        assertNull(covers["/Music/Tryptify/03.flac"])
    }

    @Test
    fun theFirstCoverInAFolderIsTheOneShared() {
        val covers = AlbumArtShare.coversByPath(
            listOf(m("/A/1.flac", null), m("/A/2.flac", "art-2"), m("/A/3.flac", "art-3")),
            placeholder = false,
        )
        assertEquals("art-2", covers["/A/1.flac"])
    }
}
