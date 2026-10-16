// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.max
import kotlin.math.min
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * The stage behind the play: a dance pad seen from above under two speaker
 * cabinets, in a dark blue room, drawn in flat cartoon lines.
 *
 * Gameplay only. The pad's arrow panels light as the player steps: softly
 * while a lane is held, and a brighter flash when a note in it is hit, so the
 * room answers the feet the way a cabinet's pad does. Everything else is
 * still: a stage that moved on its own would pull the eye off the lanes.
 *
 * The room is drawn once per size into its own layer and replayed, and it sits
 * under a dark veil, so it costs the playfield nothing and the arrows always
 * read first. The lights are a second, thin layer of at most eight rectangles.
 */
@Composable
fun GlyphDanceStage(
    heldLanes: Set<GlyphLane>,
    explosionProvider: () -> Map<GlyphLane, LaneFlash>,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Its own layer: the playfield redraws every frame above it, and
        // without one this would be redrawn with it.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val room = Brush.radialGradient(
                        0f to ROOM_GLOW,
                        0.34f to ROOM_MID,
                        0.7f to ROOM_DARK,
                        center = Offset(size.width / 2f, size.height * 0.66f),
                        radius = max(size.width, size.height) * 0.75f,
                    )
                    val arrow = arrowPath()
                    onDrawBehind { drawStage(room, arrow) }
                },
        )

        // The frame clock only schedules the next draw; the flashes are
        // timed on the wall clock, as the playfield's explosions are.
        val tick = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { tick.longValue = it }
        }
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
            @Suppress("UNUSED_EXPRESSION") tick.longValue
            drawLights(heldLanes, explosionProvider(), reducedMotion, System.nanoTime())
        }
    }
}

/** The pad's side and the unit its parts are drawn in, from the room's size. */
private fun DrawScope.padSide(): Float = min(size.width * PAD_WIDTH_SHARE, size.height * PAD_HEIGHT_SHARE)

private fun DrawScope.padLeft(side: Float): Float = (size.width - side) / 2f

private fun DrawScope.padTop(): Float = size.height * PAD_TOP_SHARE

/** Which of the pad's 3 by 3 panels a lane is: its column. */
private fun column(lane: GlyphLane): Int = when (lane) {
    GlyphLane.LEFT -> 0
    GlyphLane.DOWN, GlyphLane.UP -> 1
    GlyphLane.RIGHT -> 2
}

/** Which of the pad's 3 by 3 panels a lane is: its row. */
private fun row(lane: GlyphLane): Int = when (lane) {
    GlyphLane.UP -> 0
    GlyphLane.LEFT, GlyphLane.RIGHT -> 1
    GlyphLane.DOWN -> 2
}

/** Up and down pink, left and right cyan, as on a cabinet's pad. */
private fun panelColour(lane: GlyphLane): Color =
    if (lane == GlyphLane.UP || lane == GlyphLane.DOWN) PINK else CYAN

private fun turn(lane: GlyphLane): Float = when (lane) {
    GlyphLane.LEFT -> -90f
    GlyphLane.DOWN -> 180f
    GlyphLane.UP -> 0f
    GlyphLane.RIGHT -> 90f
}

/** An arrow pointing up, on a 24-unit square. */
private fun arrowPath(): Path = Path().apply {
    moveTo(12f, 3.5f)
    lineTo(20.5f, 12.5f)
    lineTo(15f, 12.5f)
    lineTo(15f, 20.5f)
    lineTo(9f, 20.5f)
    lineTo(9f, 12.5f)
    lineTo(3.5f, 12.5f)
    close()
}

private fun DrawScope.drawStage(room: Brush, arrow: Path) {
    val w = size.width
    val h = size.height
    val side = padSide()
    val u = side / PAD_UNITS
    val padLeft = padLeft(side)
    val padTop = padTop()

    drawRect(brush = room)
    drawRect(SIDE_WALL, topLeft = Offset.Zero, size = Size(w * SIDE_WALL_SHARE, h))
    drawRect(SIDE_WALL, topLeft = Offset(w * (1f - SIDE_WALL_SHARE), 0f), size = Size(w * SIDE_WALL_SHARE, h))

    // The speakers and the amp between them, above the pad.
    val rigLeft = (w - RIG_UNITS * u) / 2f
    val rigTop = padTop - RIG_ABOVE_PAD_UNITS * u
    drawSpeaker(rigLeft, rigTop, u)
    drawAmp(rigLeft + 130f * u, rigTop + 10f * u, u)
    drawSpeaker(rigLeft + 220f * u, rigTop, u)

    // The pad: its shadow on the floor, the frame, then the nine panels.
    drawRoundRect(
        Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(padLeft, padTop + 18f * u),
        size = Size(side, side),
        cornerRadius = CornerRadius(14f * u),
    )
    drawOutlined(FRAME, Offset(padLeft, padTop), Size(side, side), 12f * u, 3f * u)
    for (r in 0 until 3) for (c in 0 until 3) {
        val left = padLeft + (PANEL_INSET + c * PANEL_PITCH) * u
        val top = padTop + (PANEL_INSET + r * PANEL_PITCH) * u
        val panel = PANEL_SIZE * u
        drawOutlined(PANEL, Offset(left, top), Size(panel, panel), 6f * u, 2f * u)
        val bolt = 3.5f * u
        val near = 9.5f * u
        val far = panel - near
        for (bx in floatArrayOf(near, far)) for (by in floatArrayOf(near, far)) {
            drawCircle(BOLT, radius = bolt, center = Offset(left + bx, top + by))
        }
        val lane = GlyphLane.entries.firstOrNull { column(it) == c && row(it) == r } ?: continue
        // The arrow's black well, and the arrow in it.
        val well = 9f * u
        drawRoundRect(
            WELL,
            topLeft = Offset(left + well, top + well),
            size = Size(panel - well * 2f, panel - well * 2f),
            cornerRadius = CornerRadius(22f * u),
        )
        val glyph = (panel - well * 2f) / 24f
        translate(left + well, top + well) {
            scale(glyph, glyph, pivot = Offset.Zero) {
                rotate(turn(lane), pivot = Offset(12f, 12f)) {
                    drawPath(arrow, panelColour(lane))
                    drawPath(arrow, Color.White, style = Stroke(width = 0.9f, join = StrokeJoin.Round))
                }
            }
        }
    }

    // The veil: the stage is the room, the lanes are the game.
    drawRect(VEIL)
}

/** A cabinet: a tweeter over a woofer with a dotted grille. */
private fun DrawScope.drawSpeaker(left: Float, top: Float, u: Float) {
    drawOutlined(CABINET, Offset(left, top), Size(110f * u, 166f * u), 6f * u, 3f * u)
    val cx = left + 55f * u
    drawCircle(CONE_DARK, radius = 15f * u, center = Offset(cx, top + 29f * u))
    drawCircle(LINE, radius = 15f * u, center = Offset(cx, top + 29f * u), style = Stroke(3f * u))
    val woofer = Offset(cx, top + 102f * u)
    drawCircle(CONE_RIM, radius = 44f * u, center = woofer)
    drawCircle(LINE, radius = 44f * u, center = woofer, style = Stroke(3f * u))
    val inner = 34f * u
    drawCircle(CONE_DARK, radius = inner, center = woofer)
    val grille = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center = woofer, radius = inner))
    }
    clipPath(grille) {
        val pitch = 9f * u
        var y = woofer.y - inner
        while (y <= woofer.y + inner) {
            var x = woofer.x - inner
            while (x <= woofer.x + inner) {
                drawCircle(GRILLE, radius = 2f * u, center = Offset(x, y))
                x += pitch
            }
            y += pitch
        }
    }
    drawCircle(LINE, radius = inner, center = woofer, style = Stroke(2f * u))
}

private fun DrawScope.drawAmp(left: Float, top: Float, u: Float) {
    drawOutlined(AMP, Offset(left, top), Size(70f * u, 150f * u), 4f * u, 3f * u)
    drawOutlined(CABINET, Offset(left + 12f * u, top + 16f * u), Size(46f * u, 22f * u), 2f * u, 2f * u)
    drawOutlined(AMP_DARK, Offset(left + 12f * u, top + 54f * u), Size(46f * u, 60f * u), 2f * u, 2f * u)
}

/** A rounded box in [fill] with the cartoon's black line round it. */
private fun DrawScope.drawOutlined(fill: Color, topLeft: Offset, extent: Size, corner: Float, line: Float) {
    drawRoundRect(fill, topLeft = topLeft, size = extent, cornerRadius = CornerRadius(corner))
    drawRoundRect(LINE, topLeft = topLeft, size = extent, cornerRadius = CornerRadius(corner), style = Stroke(line))
}

/**
 * Each lane's panel light: [HELD_LIGHT] while it is held, and a flash from
 * [HIT_LIGHT] down to nothing over [HIT_LIGHT_SECONDS] after a hit in it.
 * Reduced motion keeps the held light and drops the flash.
 */
private fun DrawScope.drawLights(
    heldLanes: Set<GlyphLane>,
    flashes: Map<GlyphLane, LaneFlash>,
    reducedMotion: Boolean,
    now: Long,
) {
    val side = padSide()
    val u = side / PAD_UNITS
    val padLeft = padLeft(side)
    val padTop = padTop()
    for (lane in GlyphLane.entries) {
        val amount = stageLight(
            held = lane in heldLanes,
            flash = if (reducedMotion) null else flashes[lane],
            now = now,
        )
        if (amount <= LIGHT_THRESHOLD) continue
        val left = padLeft + (PANEL_INSET + column(lane) * PANEL_PITCH) * u
        val top = padTop + (PANEL_INSET + row(lane) * PANEL_PITCH) * u
        val panel = PANEL_SIZE * u
        val spill = 12f * u
        val colour = panelColour(lane)
        drawRoundRect(
            colour,
            topLeft = Offset(left - spill, top - spill),
            size = Size(panel + spill * 2f, panel + spill * 2f),
            cornerRadius = CornerRadius(16f * u),
            alpha = amount * 0.3f,
            blendMode = BlendMode.Plus,
        )
        drawRoundRect(
            colour,
            topLeft = Offset(left, top),
            size = Size(panel, panel),
            cornerRadius = CornerRadius(6f * u),
            alpha = amount,
            blendMode = BlendMode.Plus,
        )
    }
}

/** How lit a lane's panel is, 0 to 1: held, flashed by a hit [now], or both. */
internal fun stageLight(held: Boolean, flash: LaneFlash?, now: Long): Float {
    var amount = if (held) HELD_LIGHT else 0f
    if (flash != null && flash.isHit) {
        val age = (now - flash.atNanos) / 1_000_000_000f
        if (age in 0f..HIT_LIGHT_SECONDS) amount = max(amount, HIT_LIGHT * (1f - age / HIT_LIGHT_SECONDS))
    }
    return amount
}

internal const val HELD_LIGHT = 0.3f
internal const val HIT_LIGHT = 0.6f
internal const val HIT_LIGHT_SECONDS = 0.3f
private const val LIGHT_THRESHOLD = 0.01f

/** The pad, 324 units across: nine 100-unit panels on a 104-unit pitch. */
private const val PAD_UNITS = 324f
private const val PANEL_SIZE = 100f
private const val PANEL_PITCH = 104f
private const val PANEL_INSET = 6f
/** The speakers and amp, 330 units across, their top this far above the pad's. */
private const val RIG_UNITS = 330f
private const val RIG_ABOVE_PAD_UNITS = 232f

/** The pad is this much of the width, or of the height when that is smaller. */
private const val PAD_WIDTH_SHARE = 0.875f
private const val PAD_HEIGHT_SHARE = 0.5f
private const val PAD_TOP_SHARE = 0.4f
private const val SIDE_WALL_SHARE = 0.09f

private val ROOM_GLOW = Color(0xFF3A5AA0)
private val ROOM_MID = Color(0xFF1D2F66)
private val ROOM_DARK = Color(0xFF0D1638)
private val SIDE_WALL = Color(0xFF0A1230)
private val LINE = Color(0xFF141823)
private val CABINET = Color(0xFFC8CED8)
private val CONE_RIM = Color(0xFF9AA3B4)
private val CONE_DARK = Color(0xFF5D6678)
private val GRILLE = Color(0xFF2B3140)
private val AMP = Color(0xFF474E60)
private val AMP_DARK = Color(0xFF2E3442)
private val FRAME = Color(0xFFB4BCCB)
private val PANEL = Color(0xFFDDE3EC)
private val BOLT = Color(0xFF8B95A8)
private val WELL = Color(0xFF101218)
private val PINK = Color(0xFFFF2FB4)
private val CYAN = Color(0xFF3AE6FF)
private val VEIL = Color(0xFF060A1C).copy(alpha = 0.42f)
