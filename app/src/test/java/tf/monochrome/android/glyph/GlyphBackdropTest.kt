// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer
import tf.monochrome.android.glyph.pack.StepManiaPack
import tf.monochrome.android.ui.glyph.GLYPH_COLOR_BLEND_MAX_MS
import tf.monochrome.android.ui.glyph.gameplayCoverUri
import tf.monochrome.android.ui.glyph.glyphBlendMillis
import tf.monochrome.android.ui.theme.ColorBlend

/**
 * What is behind the game: the song list's picture for each song, and the
 * player's background in play.
 */
class GlyphBackdropTest {

    private fun song(background: String? = null, artwork: String? = null) = GlyphSong(
        trackId = "t",
        chartId = "c",
        title = "Song",
        artist = "Artist",
        filePath = "/x/song.ogg",
        artworkUri = artwork,
        durationSeconds = 120,
        codec = AudioCodec.UNKNOWN,
        bpm = 170f,
        difficulties = emptyList(),
        chartState = GlyphChartState.READY,
        backgroundUri = background,
    )

    @Test
    fun aSongShowsItsChartBackgroundFirst() {
        assertEquals("bg.png", song(background = "bg.png", artwork = "jacket.png").backdropUri)
    }

    @Test
    fun withoutOneItShowsItsArtwork() {
        assertEquals("cover.jpg", song(artwork = "cover.jpg").backdropUri)
    }

    @Test
    fun withNeitherTheListDrawsItsGrid() {
        assertNull(song().backdropUri)
    }

    @Test
    fun theBackgroundIsWhatTheChartNamesAndNothingElse() {
        val ssc = "#TITLE:Rock Rock On;\n#BANNER:rro-bn.png;\n#BACKGROUND:Art\\RRO-BG.PNG;\n#NOTES:"
        val files = listOf("rro-bn.png", "rro-bg.png", "song.ogg")
        // Named with a Windows path and another case, as packs made on Windows are.
        assertEquals("rro-bg.png", StepManiaPack.backgroundName(files, ssc))
        // A banner is cut for a row, not a screen: no background means none.
        assertNull(StepManiaPack.backgroundName(files, "#BANNER:rro-bn.png;"))
        // Named but not in the folder.
        assertNull(StepManiaPack.backgroundName(listOf("song.ogg"), ssc))
    }

    @Test
    fun thePictureMovesAtThePreviewsOwnSpeed() {
        // The list's picture borrows these; a change here changes both.
        assertEquals(120f, GlyphPreviewPlayer.FADE_IN_MILLIS, 0f)
        assertEquals(250L, GlyphPreviewPlayer.STOP_FADE_MILLIS)
    }

    @Test
    fun thePlayIsBehindTheAlbumArtAsInThePlayer() {
        // The album art, as the player shows it, even when the chart has a picture.
        assertEquals("art", gameplayCoverUri(song(background = "bg", artwork = "art")))
        // The chart's own picture only for a song with no art.
        assertEquals("bg", gameplayCoverUri(song(background = "bg")))
        // Neither, or no song: the wash and the glow alone.
        assertNull(gameplayCoverUri(song()))
        assertNull(gameplayCoverUri(null))
    }

    @Test
    fun aSongsColoursArriveWithinHalfASecond() {
        assertEquals(500, GLYPH_COLOR_BLEND_MAX_MS)
        // The player's longest blend, and anything past it, is held to the cap.
        assertEquals(500, glyphBlendMillis(ColorBlend.MAX_MS))
        assertEquals(500, glyphBlendMillis(60_000))
        assertEquals(500, glyphBlendMillis(750))
        // A shorter choice is kept, Instant included.
        assertEquals(250, glyphBlendMillis(250))
        assertEquals(0, glyphBlendMillis(0))
        // An old build's "match blend" (-1) reads as the default, inside the cap.
        assertEquals(ColorBlend.DEFAULT_MS.coerceAtMost(500), glyphBlendMillis(-1))
    }
}
