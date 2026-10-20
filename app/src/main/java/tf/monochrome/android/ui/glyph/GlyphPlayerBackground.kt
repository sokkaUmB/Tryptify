package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.ui.player.AlbumColors
import tf.monochrome.android.ui.player.DynamicAlbumGlow
import tf.monochrome.android.ui.player.PlayerBlurredArtBackground
import tf.monochrome.android.ui.player.dithered
import tf.monochrome.android.ui.player.dynamicPlayerBackground
import tf.monochrome.android.ui.player.rememberAlbumColors
import tf.monochrome.android.ui.theme.ColorBlend
import tf.monochrome.android.ui.theme.motionMillis

/**
 * What is behind the play: the player's own background, for the song being
 * played. The album-coloured wash, the cover blurred and darkened over it,
 * and the glow, in the player's order and from the player's own composables,
 * so the two screens are one look rather than two that drift apart.
 *
 * The colours follow the player's rule ([albumColored] is both the Dynamic
 * Colors switch and the player's own): with either off, the theme accent. On
 * a phone running the legacy player the blurred cover is left out, as that
 * player leaves it out: a full-screen blur is the expensive part, and here it
 * sits under a playfield that redraws every frame.
 *
 * The ambient visualizer is not drawn here even when it is the player's
 * background. Its engine runs one view at a time, and the game's music plays
 * through its own transport, which feeds the visualizer nothing.
 */
@Composable
internal fun GlyphPlayerBackground(
    coverUri: String?,
    albumColored: Boolean,
    colorTransitionMs: Int,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val colors = if (albumColored) {
        rememberAlbumColors(coverUri)
    } else {
        AlbumColors(dominant = accent, vibrant = accent)
    }
    val blendMillis = motionMillis(glyphBlendMillis(colorTransitionMs))
    val legacy = LocalLowPerformance.current.legacyPlayer
    Box(modifier.fillMaxSize()) {
        // Its own node, so the dither wraps the gradient alone.
        Box(
            Modifier
                .matchParentSize()
                .dithered()
                .background(dynamicPlayerBackground(colors.dominant)),
        )
        if (!legacy) {
            PlayerBlurredArtBackground(
                coverUrl = coverUri,
                albumColors = colors,
                blendMillis = blendMillis,
            )
        }
        DynamicAlbumGlow(colors.dominant)
    }
}

/**
 * The player's colour settings the background follows: [albumColored] is the
 * Dynamic Colors switch and the player's own together, and [colorTransitionMs]
 * paces the cover's dissolve. Defaults are the player's.
 */
data class GlyphBackdropLook(
    val albumColored: Boolean = false,
    val colorTransitionMs: Int = ColorBlend.DEFAULT_MS,
)

/**
 * The picture behind a song's play: its album art, as the player shows, and
 * the chart's own background only for a song that has no art.
 */
internal fun gameplayCoverUri(song: GlyphSong?): String? =
    song?.let { it.artworkUri ?: it.backgroundUri }

/**
 * The longest a colour change takes in Trypt Mania. Songs are skipped through
 * on the wheel, and at the player's longer blends a colour was still arriving
 * two picks later. Half a second keeps it a fade and never a lag.
 */
const val GLYPH_COLOR_BLEND_MAX_MS = 500

/**
 * The listener's blend length ([ColorBlend.millisFor] of the stored
 * "Blend between tracks"), capped at [GLYPH_COLOR_BLEND_MAX_MS] for this mode.
 * Shorter choices, Instant included, are kept as they are.
 */
fun glyphBlendMillis(storedMs: Int): Int =
    ColorBlend.millisFor(storedMs).coerceAtMost(GLYPH_COLOR_BLEND_MAX_MS)
