package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.glyph.multiplayer.RoomPlayer

/** One card on the room's board: a player as they stand right now. */
data class RoomBoardRow(
    val id: String,
    val name: String,
    /** Their place in the whole room, 1 first, whether or not every place is shown. */
    val rank: Int,
    val score: Long,
    val combo: Int,
    /** An empty life bar: still in the ranking, at the bottom of its order. */
    val failed: Boolean,
    val isMe: Boolean,
)

/**
 * The board's cards, best first: the top [maxRows], or, when this phone is
 * further down, the top `maxRows - 1` and this phone pinned last with its real
 * place, as osu! keeps you on its board. A failed run ranks under every live
 * one; equal scores keep the room's own order, so cards do not swap back and
 * forth on a tie.
 */
fun roomBoardRows(players: List<RoomPlayer>, myId: String?, maxRows: Int): List<RoomBoardRow> {
    if (players.isEmpty() || maxRows <= 0) return emptyList()
    val ranked = players
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<RoomPlayer>> { it.value.failedRun }
                .thenByDescending { it.value.live.score }
                .thenBy { it.index },
        )
        .mapIndexed { place, (_, p) ->
            RoomBoardRow(
                id = p.id,
                name = p.name,
                rank = place + 1,
                score = p.live.score,
                combo = p.live.combo,
                failed = p.failedRun,
                isMe = p.id == myId,
            )
        }
    if (ranked.size <= maxRows) return ranked
    val top = ranked.take(maxRows)
    if (top.any { it.isMe }) return top
    val me = ranked.firstOrNull { it.isMe } ?: return top
    return ranked.take(maxRows - 1) + me
}

private val RoomPlayer.failedRun: Boolean get() = finished?.failed == true || live.hp <= 0.0

/**
 * A room's live scores down the side of the play, as osu! has them: a card
 * per player with their place, name, score and combo, this phone's lit.
 *
 * When a place changes the cards slide to their new order, and the one that
 * climbed glows green for a moment with a rising mark. The slide and the
 * glow run in the draw phase (a layer's translation, a drawn fill), so the
 * board recomposes only when the scores arrive, four times a second; each
 * score rolls up to its new value in its own small scope. Under reduced
 * motion the cards jump to their places and nothing glows or rolls.
 */
@Composable
fun RoomLeaderboard(
    rows: List<RoomBoardRow>,
    fonts: GlyphStepFonts,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val step = BOARD_ROW_HEIGHT + BOARD_ROW_GAP
    Box(
        modifier = modifier
            .width(BOARD_WIDTH)
            .height(step * rows.size)
            .semantics {
                contentDescription = rows.joinToString(", ") { "${it.rank}. ${it.name}, ${it.score}" }
            },
    ) {
        rows.forEachIndexed { slot, row ->
            // Keyed by the player, so a card keeps its animations as it moves.
            key(row.id) {
                BoardCard(row = row, slot = slot, step = step, fonts = fonts, reducedMotion = reducedMotion)
            }
        }
    }
}

@Composable
private fun BoardCard(
    row: RoomBoardRow,
    slot: Int,
    step: Dp,
    fonts: GlyphStepFonts,
    reducedMotion: Boolean,
) {
    val stepPx = with(LocalDensity.current) { step.toPx() }
    val y = remember { Animatable(slot * stepPx) }
    LaunchedEffect(slot, stepPx) {
        val target = slot * stepPx
        if (reducedMotion) y.snapTo(target)
        else y.animateTo(target, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }

    // A climb: the place got smaller than the last one this card showed.
    var shownRank by remember { mutableIntStateOf(row.rank) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(row.rank) {
        val climbed = row.rank < shownRank
        shownRank = row.rank
        if (climbed && !reducedMotion) {
            rise.snapTo(1f)
            rise.animateTo(0f, tween(RISE_MILLIS, easing = LinearEasing))
        }
    }

    val ink = if (row.isMe) GlyphTheme.Paper else GlyphTheme.Paper.copy(alpha = 0.82f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(BOARD_ROW_HEIGHT)
            .graphicsLayer {
                translationY = y.value
                alpha = if (row.failed) 0.45f else 1f
            }
            .drawBehind {
                val corner = CornerRadius(BOARD_CORNER.toPx())
                drawRoundRect(
                    color = if (row.isMe) GlyphTheme.InkRaised.copy(alpha = 0.82f) else GlyphTheme.Ink.copy(alpha = 0.55f),
                    cornerRadius = corner,
                )
                if (rise.value > 0f) {
                    drawRoundRect(color = GlyphTheme.Positive.copy(alpha = 0.38f * rise.value), cornerRadius = corner)
                }
                if (row.isMe) {
                    val inset = 0.75.dp.toPx()
                    drawRoundRect(
                        color = GlyphTheme.Paper.copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = corner,
                        style = Stroke(width = inset * 2),
                    )
                }
            }
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = "${row.rank}",
            style = fonts.label,
            color = if (row.rank == 1 && !row.failed) GlyphTheme.Warning else ink,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = row.name,
            style = fonts.label,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "▲",
            style = fonts.label,
            color = GlyphTheme.Positive,
            modifier = Modifier.graphicsLayer { alpha = rise.value },
        )
        Spacer(Modifier.width(4.dp))
        RollingScore(score = row.score, combo = row.combo, fonts = fonts, color = ink, reducedMotion = reducedMotion)
    }
}

/** The score rolling up to its new value, and the combo under it; its own scope, so only it redraws. */
@Composable
private fun RollingScore(score: Long, combo: Int, fonts: GlyphStepFonts, color: androidx.compose.ui.graphics.Color, reducedMotion: Boolean) {
    val shown by animateIntAsState(
        targetValue = score.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        animationSpec = if (reducedMotion) snap() else tween(SCORE_ROLL_MS, easing = LinearEasing),
        label = "board score",
    )
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.End) {
        Text(text = "%,d".format(shown), style = fonts.label, color = color, maxLines = 1)
        if (combo > 0) {
            Text(text = "${combo}x", style = fonts.label, color = GlyphTheme.Muted, maxLines = 1)
        }
    }
}

private val BOARD_WIDTH = 168.dp
private val BOARD_ROW_HEIGHT = 38.dp
private val BOARD_ROW_GAP = 4.dp
private val BOARD_CORNER = 8.dp

/** How long a climb glows. */
private const val RISE_MILLIS = 900

/** A score rolls over the gap between two of the server's frames (250 ms). */
private const val SCORE_ROLL_MS = 240
