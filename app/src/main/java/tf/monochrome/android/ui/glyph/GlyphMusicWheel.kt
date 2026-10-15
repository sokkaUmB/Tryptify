// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.view.KeyEvent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.liquidGlass

/**
 * StepMania's music wheel: the songs and their folders on a turning wheel, the
 * one at the centre chosen.
 *
 * Built the way StepMania's is (`[MusicWheel]` in its `metrics.ini`): items on
 * an arc that bends away from the centre (`ItemTransformFunction`'s cosine),
 * the wheel wrapping round end to end, a short spring between items
 * (`SwitchSeconds=0.10`). On a phone it turns by dragging and flinging and
 * settles on an item; a tap brings an item to the centre, and a tap on the
 * centre one opens it — a folder opens or closes, a song is played. A tap on a
 * song away from the centre is a choice too, made now rather than after the
 * wheel rests ([onPick]). A keyboard turns it with the arrows and opens with
 * Enter.
 *
 * The position is one float, items apart; a frame moves the items' layers and
 * recomposes nothing until the centre changes to another item.
 *
 * [anchor] is the item to centre whenever [items] changes: the wheel keeps its
 * place across a folder opening, a sort or a search.
 *
 * [bottomInset] is how much of the wheel's foot is under glass (the chosen
 * song's panel, the tab bar). The wheel still runs to its foot, under the
 * glass, and its centre rises by half the inset to stay in the middle of what
 * is left.
 */
@Composable
fun GlyphMusicWheel(
    items: List<GlyphWheel.Item>,
    anchor: Int,
    selectedTrackId: String?,
    onFocus: (GlyphWheel.Item) -> Unit,
    onActivate: (GlyphWheel.Item) -> Unit,
    modifier: Modifier = Modifier,
    onPick: (GlyphWheel.Item) -> Unit = {},
    bottomInset: Dp = 0.dp,
    requestFocus: Boolean = true,
) {
    val count = items.size
    if (count == 0) return
    val density = LocalDensity.current
    val itemHeight = MonoDimens.listRowHeight
    val spacingPx = with(density) { (itemHeight + ITEM_GAP).toPx() }
    val arcPx = with(density) { ARC_REACH.toPx() }
    val scope = rememberCoroutineScope()
    val focusNow by rememberUpdatedState(onFocus)
    val activateNow by rememberUpdatedState(onActivate)
    val pickNow by rememberUpdatedState(onPick)
    val currentItems by rememberUpdatedState(items)

    var position by remember { mutableFloatStateOf(anchor.coerceIn(0, count - 1).toFloat()) }
    var settling by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val heightPx = with(density) { maxHeight.toPx() }
        // Never so far that the centre item leaves the top of the wheel, as a
        // tall panel on a short screen would otherwise make it.
        val liftPx = (with(density) { bottomInset.toPx() } / 2f)
            .coerceAtMost((heightPx / 2f - spacingPx / 2f).coerceAtLeast(0f))
        // Enough on each side to fill the wheel, and one more to scroll in.
        val reach = ceil(heightPx / 2f / spacingPx).toInt() + 1
        // Below the lifted centre there is more wheel than above it.
        val drawReach = ceil((heightPx / 2f + liftPx) / spacingPx).toInt() + 1
        // Wrapping a handful round would show the same few over and over; the
        // wheel wraps once it is longer than it is tall, as StepMania's is.
        // Counted on the whole height, so the panel coming and going never
        // turns wrapping on or off under the player's thumb.
        val wraps = count > reach * 2
        fun clamp(value: Float) = if (wraps) value else value.coerceIn(0f, (count - 1).toFloat())
        fun indexAt(slot: Int): Int? = when {
            wraps -> ((slot % count) + count) % count
            slot in 0 until count -> slot
            else -> null
        }

        // A new set of items (a folder opened, a sort, a search): back to the anchor.
        LaunchedEffect(items, wraps) {
            settling?.cancel()
            position = anchor.coerceIn(0, count - 1).toFloat()
        }

        fun settleTo(target: Float, velocity: Float = 0f) {
            settling?.cancel()
            settling = scope.launch {
                animate(position, clamp(target), velocity, spring(dampingRatio = 0.9f, stiffness = 420f)) { value, _ ->
                    position = value
                }
            }
        }
        fun step(by: Int) = settleTo(position.roundToInt().toFloat() + by)
        val centre by remember { derivedStateOf { position.roundToInt() } }
        fun activateCentre() {
            indexAt(centre)?.let { activateNow(currentItems[it]) }
        }

        // The centred item, each time it changes, for the banner and the preview.
        LaunchedEffect(count, wraps) {
            snapshotFlow { centre }
                .distinctUntilChanged()
                .collect { slot -> indexAt(slot)?.let { focusNow(currentItems[it]) } }
        }

        val focus = remember { FocusRequester() }
        LaunchedEffect(requestFocus) { if (requestFocus) runCatching { focus.requestFocus() } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(count, wraps) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            settling?.cancel()
                            tracker.resetTracking()
                        },
                        onVerticalDrag = { change, dy ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            position = clamp(position - dy / spacingPx)
                        },
                        onDragEnd = {
                            val velocity = -tracker.calculateVelocity().y / spacingPx
                            // Where a fling would coast to, settled on the item there.
                            val coast = exponentialDecay<Float>(frictionMultiplier = FRICTION)
                                .calculateTargetValue(position, velocity)
                            settleTo(coast.roundToInt().toFloat(), velocity)
                        },
                        onDragCancel = { settleTo(position.roundToInt().toFloat()) },
                    )
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                        KeyEvent.KEYCODE_PAGE_UP -> { step(-PAGE); true }
                        KeyEvent.KEYCODE_PAGE_DOWN -> { step(PAGE); true }
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> { activateCentre(); true }
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .semantics {
                    contentDescription = "Music wheel"
                    customActions = listOf(
                        CustomAccessibilityAction("Previous") { step(-1); true },
                        CustomAccessibilityAction("Next") { step(1); true },
                    )
                },
        ) {
            for (slot in centre - drawReach..centre + drawReach) {
                val index = indexAt(slot) ?: continue
                val item = items[index]
                key(slot, item.key) {
                    WheelEntry(
                        item = item,
                        centred = slot == centre,
                        selected = item is GlyphWheel.Item.Song && item.song.trackId == selectedTrackId,
                        onClick = {
                            if (slot == centre) {
                                activateNow(item)
                            } else {
                                settleTo(slot.toFloat())
                                pickNow(item)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(itemHeight)
                            .graphicsLayer {
                                // Read here, in the layer: the wheel turning
                                // moves layers and recomposes nothing.
                                val offset = slot - position
                                val distance = abs(offset)
                                translationY = offset * spacingPx - liftPx
                                // StepMania's arc: items bend away from the centre.
                                translationX = (1f - cos(offset * ARC_ANGLE)) * arcPx
                                val scale = 1f - (distance.coerceAtMost(4f) * 0.035f)
                                scaleX = scale
                                scaleY = scale
                                alpha = (1f - distance / (reach + 0.5f)).coerceIn(0.12f, 1f)
                            },
                    )
                }
            }
        }
    }
}

/** One item on the wheel: a folder, or a song inside the open one. */
@Composable
private fun WheelEntry(
    item: GlyphWheel.Item,
    centred: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val description = when (item) {
        is GlyphWheel.Item.Folder -> "${item.section.name}, folder, ${item.section.songs.size} songs, " +
            if (item.open) "open" else "closed"
        // As the song rows read before the wheel: nothing a listener had is lost.
        is GlyphWheel.Item.Song -> buildString {
            val song = item.song
            append(song.title)
            append(", ").append(song.artist)
            append(", ").append(song.formattedDuration)
            append(", ").append(song.codec.name)
            if (song.hasChart) {
                append(", ").append(song.bpmLabel)
                append(", ").append(song.difficulties.size).append(" difficulties")
            } else {
                append(", ").append(song.chartState.label)
            }
        }
    }
    Surface(
        modifier = modifier
            .padding(horizontal = MonoDimens.listItemPaddingH)
            .clickable(
                onClickLabel = when {
                    !centred -> "Bring to the centre"
                    item is GlyphWheel.Item.Folder -> if (item.open) "Close folder" else "Open folder"
                    else -> "Choose"
                },
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                // Spoken by the framework, never left to the tint.
                this.selected = selected
                if (centred) stateDescription = "In the centre"
            }
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = when {
            centred -> scheme.primary.copy(alpha = 0.22f)
            selected -> scheme.primary.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
    ) {
        when (item) {
            is GlyphWheel.Item.Folder -> FolderEntry(item, centred)
            is GlyphWheel.Item.Song -> SongEntry(item, centred)
        }
    }
}

@Composable
private fun FolderEntry(item: GlyphWheel.Item.Folder, centred: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val section = item.section
    Row(
        modifier = Modifier.fillMaxSize().padding(end = MonoDimens.listItemPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The folder's colour, as StepMania gives each section one.
        Box(
            Modifier
                .width(6.dp)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(sectionColour(section.name)),
        )
        Spacer(Modifier.width(MonoDimens.spacingMd))
        Icon(
            if (item.open) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            tint = scheme.onSurface,
        )
        Spacer(Modifier.width(MonoDimens.spacingMd))
        Column(Modifier.weight(1f)) {
            Text(
                text = section.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (centred) FontWeight.Bold else FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${section.songs.size} song${if (section.songs.size == 1) "" else "s"} · ${section.charted} ready",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            if (item.open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SongEntry(item: GlyphWheel.Item.Song, centred: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val song = item.song
    Row(
        modifier = Modifier
            .fillMaxSize()
            // In from the folders, as a folder's songs sit under it.
            .padding(start = MonoDimens.spacingLg, end = MonoDimens.listItemPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            url = song.artworkUri,
            contentDescription = null,
            size = MonoDimens.coverList,
            cornerRadius = MonoDimens.radiusSm,
        )
        Spacer(Modifier.width(MonoDimens.spacingMd))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (centred) FontWeight.Bold else FontWeight.Normal,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist.ifBlank { song.formattedDuration },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(MonoDimens.spacingSm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (song.hasChart) song.bpmLabel else song.chartState.label,
                style = MaterialTheme.typography.labelSmall,
                color = when (song.chartState) {
                    GlyphChartState.READY -> scheme.onSurface
                    GlyphChartState.UNREADABLE -> scheme.error
                    GlyphChartState.NOT_GENERATED -> scheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            if (song.hasChart) {
                Text(
                    text = song.difficulties.joinToString(" ") { it.sscName.take(1) },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A folder's colour: steady per name, mid-tone so it reads on light and dark glass. */
internal fun sectionColour(name: String): Color {
    val hue = ((name.lowercase().hashCode() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.55f, 0.85f)
}

private val ITEM_GAP = 6.dp
/** How far the arc carries an item sideways at its furthest. */
private val ARC_REACH = 150.dp
/** Radians of arc per item from the centre. */
private const val ARC_ANGLE = 0.26f
private const val FRICTION = 1.4f
private const val PAGE = 5
