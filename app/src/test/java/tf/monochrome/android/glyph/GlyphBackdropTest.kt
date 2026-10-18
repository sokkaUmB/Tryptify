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
import tf.monochrome.android.ui.glyph.HELD_LIGHT
import tf.monochrome.android.ui.glyph.HIT_LIGHT
import tf.monochrome.android.ui.glyph.HIT_LIGHT_SECONDS
import tf.monochrome.android.ui.glyph.LaneFlash
import tf.monochrome.android.ui.glyph.stageLight

/**
 * What is behind the game: the song list's picture for each song, and the
 * stage's pad lights in play.
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
    fun aHeldPanelGlowsSoftly() {
        assertEquals(HELD_LIGHT, stageLight(held = true, flash = null, now = 0L), 0f)
        assertEquals(0f, stageLight(held = false, flash = null, now = 0L), 0f)
    }

    @Test
    fun aHitFlashesThePanelAndFades() {
        val at = 1_000_000_000L
        val hit = LaneFlash(atNanos = at, isHit = true)
        assertEquals(HIT_LIGHT, stageLight(held = false, flash = hit, now = at), 1e-6f)
        val half = at + (HIT_LIGHT_SECONDS * 500_000_000L).toLong()
        assertEquals(HIT_LIGHT / 2f, stageLight(held = false, flash = hit, now = half), 1e-3f)
        val gone = at + (HIT_LIGHT_SECONDS * 1_000_000_000L).toLong() + 1
        assertEquals(0f, stageLight(held = false, flash = hit, now = gone), 0f)
        // Held through the fade, the panel never drops below its held glow.
        assertEquals(HELD_LIGHT, stageLight(held = true, flash = hit, now = half + 100_000_000L), 1e-6f)
    }

    @Test
    fun aMissLightsNothing() {
        val miss = LaneFlash(atNanos = 0L, isHit = false)
        assertEquals(0f, stageLight(held = false, flash = miss, now = 0L), 0f)
    }
}
