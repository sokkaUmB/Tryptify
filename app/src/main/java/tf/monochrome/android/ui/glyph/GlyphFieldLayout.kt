// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import tf.monochrome.android.glyph.asset.GlyphLane
import kotlin.math.roundToInt

/**
 * Where the four lanes sit across a screen [widthPx] wide: a centred column
 * of whole-pixel lanes.
 *
 * Portrait gives the lanes the whole width, as it always has. Landscape gave
 * them the whole width too, which on a phone held sideways made each arrow
 * as tall as half the screen: the notes crossed most of their way in one
 * arrow's height and covered the HUD. In landscape the lanes take
 * [GlyphModifiers.landscapeFieldWidth] of the width instead, centred.
 */
data class GlyphFieldBounds(val leftPx: Int, val laneWidthPx: Int) {
    val widthPx: Int get() = laneWidthPx * LANES

    companion object {
        /** [fraction] of [widthPx], cut to a whole number of pixels a lane so no lane shimmers. */
        fun of(widthPx: Int, fraction: Float): GlyphFieldBounds {
            val lane = (widthPx * fraction.coerceIn(MIN_LANDSCAPE_FIELD, 1f) / LANES).toInt().coerceAtLeast(1)
            return GlyphFieldBounds(((widthPx - lane * LANES) / 2).coerceAtLeast(0), lane)
        }
    }
}

/**
 * Where each lane can be touched, left to right, as [start, end) pixel spans.
 *
 * The spans follow the drawn lanes, so a lane is tapped where it is seen. The
 * screen either side of a narrowed field belongs to the outer lanes: a thumb
 * resting at the edge of a phone held sideways still presses Left or Right.
 * [hitboxScale] leaves gutters between the lanes as it does in portrait; it
 * never narrows an outer lane from the screen's edge, where there is nothing
 * to tell it apart from.
 */
fun glyphLaneSpans(widthPx: Int, fieldFraction: Float, hitboxScale: Float): List<IntRange> {
    val bounds = GlyphFieldBounds.of(widthPx, fieldFraction)
    val gutter = (bounds.laneWidthPx * (1f - hitboxScale.coerceIn(MIN_HITBOX, MAX_HITBOX)) / 2f).roundToInt()
    return List(LANES) { i ->
        val laneLeft = bounds.leftPx + bounds.laneWidthPx * i
        val start = if (i == 0) 0 else laneLeft + gutter
        val end = if (i == LANES - 1) widthPx else laneLeft + bounds.laneWidthPx - gutter
        start until end
    }
}

/**
 * The share of the width the field takes on this window: the landscape
 * setting when the window is wider than tall, else the whole width.
 */
@Composable
fun glyphFieldFraction(landscapeFieldWidth: Float): Float {
    val window = LocalWindowInfo.current.containerSize
    return if (window.width > window.height) landscapeFieldWidth.coerceIn(MIN_LANDSCAPE_FIELD, 1f) else 1f
}

/** Whether the window is landscape-shaped, for what else the field changes there. */
@Composable
fun glyphIsLandscape(): Boolean {
    val window = LocalWindowInfo.current.containerSize
    return window.width > window.height
}

/**
 * Lays its content out as the field's centred column, [GlyphFieldBounds]
 * wide, the full height. The whole width when [fraction] is 1, as before.
 */
fun Modifier.glyphFieldColumn(fraction: Float): Modifier =
    if (fraction >= 1f) {
        this
    } else {
        layout { measurable, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val bounds = GlyphFieldBounds.of(width, fraction)
            val placeable = measurable.measure(Constraints.fixed(bounds.widthPx, height))
            layout(width, height) { placeable.place(bounds.leftPx, 0) }
        }
    }

private val LANES = GlyphLane.entries.size

/**
 * The receptors' distance from the bottom in landscape. Portrait's 96 dp
 * leaves room for thumbs under the receptors; held sideways the thumbs come
 * from the sides, and 96 dp was over a quarter of the height.
 */
val LANDSCAPE_RECEPTOR_INSET = 64.dp

/** The narrowest the landscape field goes: a lane still wider than a thumb on a phone. */
const val MIN_LANDSCAPE_FIELD = 0.25f

/**
 * The landscape field's default: arrows about a sixth of a phone's height,
 * StepMania's proportion with room for thumbs.
 */
const val DEFAULT_LANDSCAPE_FIELD = 0.4f
