// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer

/**
 * The picture behind the song list: the song up on the wheel's own chart
 * background, else its artwork, else nothing: a song with no picture leaves
 * the page as it is.
 *
 * It changes when the music does and at the music's speed: a new song's
 * picture comes up over [GlyphPreviewPlayer.FADE_IN_MILLIS] as its preview
 * starts, the last one goes over [GlyphPreviewPlayer.STOP_FADE_MILLIS], the
 * time a stopped preview takes to fade away, and a folder that stops the
 * music takes the picture with it. Reduced motion cuts instead.
 *
 * Under the wheel and inside the panels' haze source, so the card and the
 * pills frost it; a scrim of the page's own colour keeps the wheel's rows
 * readable over any picture, in a light theme or a dark one.
 */
@Composable
fun GlyphSongBackdrop(
    backdrop: GlyphBackdrop?,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val fadeInMs = if (reducedMotion) 0 else GlyphPreviewPlayer.FADE_IN_MILLIS.toInt()
    val fadeOutMs = if (reducedMotion) 0 else GlyphPreviewPlayer.STOP_FADE_MILLIS.toInt()
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = SCRIM_ALPHA)
    AnimatedContent(
        targetState = backdrop,
        contentKey = { it?.trackId },
        transitionSpec = { fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs)) },
        label = "song backdrop",
        modifier = modifier.fillMaxSize(),
    ) { shown ->
        // Each picture carries its own scrim, so the two leave together: a
        // scrim shared by all went at once and left the going picture bare.
        Box(modifier = Modifier.fillMaxSize()) {
            val uri = shown?.imageUri
            if (uri != null) {
                ArtBackdrop(uri = uri, fadeInMs = fadeInMs)
                Spacer(modifier = Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/**
 * A song's picture, cropped to fill. It waits for its decode before fading
 * up, so a picture that takes a moment to read never pops in after the fade;
 * one that cannot be read at all leaves the page as it is.
 */
@Composable
private fun ArtBackdrop(uri: String, fadeInMs: Int) {
    var failed by remember(uri) { mutableStateOf(false) }
    var loaded by remember(uri) { mutableStateOf(false) }
    if (failed) return
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(fadeInMs),
        label = "song backdrop decode",
    )
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true },
        onError = { failed = true },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha },
    )
}

private const val SCRIM_ALPHA = 0.6f
