// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.preview.SongPreview

/**
 * The app's [SongPreview], for song rows: given at the app's root, as
 * [LocalPlayInTryptMania] is, so every list's rows can preview without each
 * screen wiring it. Null where nothing provides it, and rows keep their old
 * long-press.
 */
val LocalSongPreview = staticCompositionLocalOf<SongPreview?> { null }

/** A row's long-press: [track]'s preview, with the long-press tick. Null where no preview is provided. */
@Composable
fun rememberHoldToPreview(track: Track): (() -> Unit)? {
    val preview = LocalSongPreview.current ?: return null
    val haptics = LocalHapticFeedback.current
    return remember(preview, track) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            preview.start(track)
        }
    }
}

/** A row's long-press: [track]'s preview, with the long-press tick. Null where no preview is provided. */
@Composable
fun rememberHoldToPreview(track: UnifiedTrack): (() -> Unit)? {
    val preview = LocalSongPreview.current ?: return null
    val haptics = LocalHapticFeedback.current
    return remember(preview, track) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            preview.start(track)
        }
    }
}

/**
 * The row's half of a hold: the preview stops when the finger lifts, and while
 * it plays the row fills from the left over its 25 s, behind its text, the
 * colour running from the accent to amber to red as the time runs out
 * ([previewFillColour]). [key] is [SongPreview.keyOf] the row's song, [shape]
 * the row's own, which the fill keeps inside.
 *
 * The lift is watched, never consumed: the row's tap and long-press and the
 * list's scroll see every event as before. A list scrolled under the held
 * finger keeps the preview playing until the finger lifts, or until the row
 * scrolls out of the list, which ends it.
 */
fun Modifier.songPreviewHold(key: String, shape: Shape): Modifier = composed {
    val preview = LocalSongPreview.current ?: return@composed this
    val state by preview.state.collectAsState()
    val held = state?.key == key
    val playing = held && state?.playing == true
    val accent = MaterialTheme.colorScheme.primary
    // Read only while drawing, so the fill moves a frame at a time without
    // recomposing the row.
    val fraction = remember { mutableFloatStateOf(0f) }
    if (playing) {
        LaunchedEffect(key) {
            while (true) withFrameMillis { fraction.floatValue = preview.elapsedFraction(key) }
        }
    }
    this
        .pointerInput(key, preview) {
            try {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    // Nothing unless this row's preview is the one playing.
                    preview.stop(key)
                }
            } finally {
                // The row left the list under the finger (scrolled away, or now
                // shows another song): its lift will never come here.
                preview.stop(key)
            }
        }
        .drawBehind {
            if (!held) return@drawBehind
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            clipPath(path) {
                if (!playing) {
                    // The song is still on its way (a stream): the row says it was heard.
                    drawRect(accent.copy(alpha = WAITING_ALPHA))
                } else {
                    val f = fraction.floatValue
                    drawRect(
                        color = previewFillColour(f, accent).copy(alpha = FILL_ALPHA),
                        size = Size(size.width * f, size.height),
                    )
                }
            }
        }
}

private val AMBER = Color(0xFFFFB300)
private val RED = Color(0xFFE53935)
private const val AMBER_AT = 0.6f
private const val FILL_ALPHA = 0.32f
private const val WAITING_ALPHA = 0.10f

/**
 * The held row's fill at [fraction] of the 25 s: the accent while most of it
 * is left, turning amber by three fifths, then red as it runs out.
 */
fun previewFillColour(fraction: Float, accent: Color): Color {
    val f = fraction.coerceIn(0f, 1f)
    return if (f < AMBER_AT) lerp(accent, AMBER, f / AMBER_AT) else lerp(AMBER, RED, (f - AMBER_AT) / (1f - AMBER_AT))
}
