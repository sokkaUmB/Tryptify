// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.player.preview

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.components.previewFillColour

/** Hold a song to hear it: where the 25 s start, how they sound, and how the row counts them. */
class SongPreviewTest {

    @Test
    fun aRememberedDropIsWhereItStarts() {
        assertEquals(95_000L, SongPreview.startMs("file", durationSeconds = 240, drop = 95f))
    }

    @Test
    fun withoutOneItStartsAThirdIn() {
        assertEquals(80_000L, SongPreview.startMs("https", durationSeconds = 240, drop = null))
    }

    @Test
    fun theTwentyFiveSecondsStayInsideTheSong() {
        // A drop too near the end starts where 25 s still fit.
        assertEquals(215_000L, SongPreview.startMs("content", durationSeconds = 240, drop = 230f))
        // A song no longer than a preview plays from the top.
        assertEquals(0L, SongPreview.startMs("file", durationSeconds = 20, drop = null))
    }

    @Test
    fun cachesThatFillFromTheFrontPlayFromTheTop() {
        assertEquals(0L, SongPreview.startMs("qobuz", durationSeconds = 240, drop = 95f))
        assertEquals(0L, SongPreview.startMs("deezer", durationSeconds = 240, drop = null))
    }

    @Test
    fun itFadesInQuicklyAndOutAtTheEnd() {
        assertEquals(0f, SongPreview.volumeAt(0L), 0f)
        assertEquals(1f, SongPreview.volumeAt(120L), 0f)
        assertEquals(1f, SongPreview.volumeAt(12_000L), 0f)
        assertTrue(SongPreview.volumeAt(24_500L) in 0.1f..0.9f)
        assertEquals(0f, SongPreview.volumeAt(SongPreview.PREVIEW_MS), 0f)
    }

    @Test
    fun aSongIsOneKeyWhereverItIsListed() {
        val track = tf.monochrome.android.domain.model.Track(id = 7L, title = "Song")
        assertEquals(SongPreview.keyOf(track), SongPreview.keyOf(track.copy(title = "Same song")))
        assertNotEquals(SongPreview.keyOf(track), SongPreview.keyOf(track.copy(id = 8L)))
    }

    @Test
    fun theFillRunsFromTheAccentThroughAmberToRed() {
        val accent = Color(0xFF3D8BFF)
        assertEquals(accent, previewFillColour(0f, accent))
        assertEquals(Color(0xFFFFB300), previewFillColour(0.6f, accent))
        assertEquals(Color(0xFFE53935), previewFillColour(1f, accent))
        // Past the ends it holds there.
        assertEquals(Color(0xFFE53935), previewFillColour(1.4f, accent))
    }
}
