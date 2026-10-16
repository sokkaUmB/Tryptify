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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tf.monochrome.android.glyph.engine.GlyphPreviewPlayer

/**
 * The picture behind the song list: the song up on the wheel's own chart
 * background, else its artwork, else the list's grid ([GlyphRankingGrid]).
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
            when {
                shown == null -> Unit
                shown.imageUri == null -> GlyphRankingGrid(Modifier.fillMaxSize())
                else -> ArtBackdrop(uri = shown.imageUri, fadeInMs = fadeInMs)
            }
            if (shown != null) {
                Spacer(modifier = Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/**
 * A song's picture, cropped to fill. It waits for its decode before fading
 * up, so a picture that takes a moment to read never pops in after the fade;
 * one that cannot be read at all is the grid.
 */
@Composable
private fun ArtBackdrop(uri: String, fadeInMs: Int) {
    var failed by remember(uri) { mutableStateOf(false) }
    var loaded by remember(uri) { mutableStateOf(false) }
    if (failed) {
        GlyphRankingGrid(Modifier.fillMaxSize())
        return
    }
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

/**
 * The grid, for a song with no picture: orange grid paper top and bottom, a
 * deep purple middle crossed by a darker band, and a faint fireball, in the
 * colours of an arcade ranking screen. Drawn once per size and still: it sits
 * under the panels' frosted glass, which would re-blur every frame of any
 * movement.
 */
@Composable
fun GlyphRankingGrid(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val band = PathParser().parsePathString(BAND).toPath()
                val bandEdge = PathParser().parsePathString(BAND_EDGE).toPath()
                val flames = FLAMES.map { PathParser().parsePathString(it).toPath() }
                val gridStep = 18.dp.toPx()
                val gridLine = 1.dp.toPx()
                onDrawBehind {
                    val w = size.width
                    val h = size.height
                    drawRect(PURPLE)

                    // The band and its lit edge, laid out on a 370 by 750 page.
                    scale(w / PAGE_W, h / PAGE_H, pivot = Offset.Zero) {
                        drawPath(band, BAND_FILL)
                        drawPath(bandEdge, BAND_LIGHT, style = Stroke(width = 4f))
                    }

                    // The fireball, its flames trailing up and to the left.
                    val ballW = w * BALL_WIDTH_SHARE
                    val unit = ballW / FLAME_W
                    // Mirrored: moved to its right edge, then flipped back across.
                    translate(w * BALL_LEFT_SHARE + ballW, h * BALL_TOP_SHARE) {
                        scale(-unit, unit, pivot = Offset.Zero) {
                            for (flame in flames) {
                                drawPath(flame, FLAME_FILL, alpha = BALL_ALPHA)
                                drawPath(flame, FLAME_LINE, alpha = BALL_ALPHA, style = Stroke(width = 7f))
                            }
                            drawCircle(BALL_FILL, radius = 72f, center = Offset(105f, 440f), alpha = BALL_ALPHA)
                            drawCircle(FLAME_LINE, radius = 72f, center = Offset(105f, 440f), alpha = BALL_ALPHA, style = Stroke(width = 7f))
                            drawCircle(BALL_SHINE, radius = 22f, center = Offset(80f, 415f), alpha = BALL_ALPHA)
                        }
                    }

                    // Grid paper top and bottom, each with its grey rule.
                    drawGridPaper(0f, h * TOP_SHARE, gridStep, gridLine)
                    drawRule(h * TOP_SHARE)
                    val bottom = h * (1f - BOTTOM_SHARE)
                    drawGridPaper(bottom, h, gridStep, gridLine)
                    drawRule(bottom - RULE_HEIGHT.dp.toPx())
                }
            },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGridPaper(top: Float, bottom: Float, step: Float, line: Float) {
    drawRect(GRID_PAPER, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
    var x = 0f
    while (x <= size.width) {
        drawRect(GRID_LINE, topLeft = Offset(x, top), size = Size(line, bottom - top))
        x += step
    }
    var y = top
    while (y <= bottom) {
        drawRect(GRID_LINE, topLeft = Offset(0f, y), size = Size(size.width, line))
        y += step
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRule(top: Float) {
    val height = RULE_HEIGHT.dp.toPx()
    val edge = 2.dp.toPx()
    drawRect(RULE_EDGE, topLeft = Offset(0f, top), size = Size(size.width, height))
    drawRect(RULE, topLeft = Offset(0f, top + edge), size = Size(size.width, height - edge * 2f))
}

/** How much of the page's own colour lies over the picture, for the rows on it. */
private const val SCRIM_ALPHA = 0.6f

private const val PAGE_W = 370f
private const val PAGE_H = 750f
private const val BAND = "M-20 236 L196 236 C228 236 246 222 262 198 L280 170 L390 170 L390 548 " +
    "L136 548 C104 548 86 562 70 586 L54 610 L-20 610 Z"
private const val BAND_EDGE = "M-20 236 L196 236 C228 236 246 222 262 198 L280 170 L390 170"

/** The flames, on a 400 by 500 square, their base at the bottom left. */
private const val FLAME_W = 400f
private val FLAMES = listOf(
    "M60 470 C70 380 110 300 170 240 C220 190 240 140 230 80 C270 140 270 210 220 270 C170 330 120 380 100 470 Z",
    "M40 480 C60 380 120 300 230 230 C300 185 340 120 330 40 C380 120 370 220 300 290 C230 360 150 400 120 480 Z",
    "M100 480 C140 400 220 360 290 340 C340 325 370 290 380 250 C400 310 380 370 320 400 C260 430 200 440 170 480 Z",
)
private const val BALL_WIDTH_SHARE = 0.86f
private const val BALL_LEFT_SHARE = 0.08f
private const val BALL_TOP_SHARE = 0.27f
private const val BALL_ALPHA = 0.6f
private const val TOP_SHARE = 0.19f
private const val BOTTOM_SHARE = 0.15f
private const val RULE_HEIGHT = 8

private val PURPLE = Color(0xFF3D1F94)
private val BAND_FILL = Color(0xFF2D1480)
private val BAND_LIGHT = Color(0xFF5A3FC0)
private val FLAME_FILL = Color(0xFF6F5BD0)
private val FLAME_LINE = Color(0xFF21106A)
private val BALL_FILL = Color(0xFF8574DC)
private val BALL_SHINE = Color(0xFFA596EC)
private val GRID_PAPER = Color(0xFFF8A51C)
private val GRID_LINE = Color(0xFFE08A08)
private val RULE = Color(0xFFA7ACBD)
private val RULE_EDGE = Color(0xFF1D0F4F)
