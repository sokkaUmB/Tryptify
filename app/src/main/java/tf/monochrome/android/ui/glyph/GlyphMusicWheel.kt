// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.view.KeyEvent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphWheel
import tf.monochrome.android.glyph.engine.GlyphWheelSound
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.theme.MonoDimens

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
 * wheel rests ([onPick]). A keyboard turns it with Up and Down and opens with
 * Enter; Left and Right are left to focus, so a D-pad can reach the panel.
 *
 * It turns through [scrollable], like any list: presses wait while a drag
 * might be starting, the tab bar folds and unfolds as it turns, and a drag
 * takes over from a settle or a key's turn without a jolt. Every turn the
 * player makes goes through it, which is how the swish ([sound]) knows a turn
 * from the wheel being set to a new place.
 *
 * The position is one float, items apart; a frame moves the items' layers and
 * recomposes nothing until the centre changes to another item.
 *
 * [anchor] is the item to centre whenever [items] changes: the wheel keeps its
 * place across a folder opening, a sort or a search, from the first frame.
 *
 * [topInset] and [bottomInset] are how much of the wheel's head and foot are
 * under glass (the search bar, the chosen song's panel, the tab bar), read in
 * the layer so their animations move layers only. The wheel still runs under
 * the glass; its centre moves to the middle of what is left.
 *
 * [foldersLocked]: the open folder cannot be closed (a search's results, a
 * lone folder), so its heading offers no "Close folder".
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
    topInset: () -> Dp = NO_INSET,
    bottomInset: () -> Dp = NO_INSET,
    foldersLocked: Boolean = false,
    sound: Boolean = true,
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
    val soundOn by rememberUpdatedState(sound)
    val topNow by rememberUpdatedState(topInset)
    val bottomNow by rememberUpdatedState(bottomInset)
    val currentItems = rememberUpdatedState(items)

    // Keyed on the items: a new set is drawn at its anchor from its first
    // frame. Set from an effect instead, the first frame drew the new items at
    // the old place — blank, or the wrong folders — before the jump.
    val position = remember(items) { mutableFloatStateOf(anchor.coerceIn(0, count - 1).toFloat()) }
    val positionNow = rememberUpdatedState(position)

    val swish = remember { GlyphWheelSound() }
    DisposableEffect(swish) { onDispose { swish.release() } }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val heightPx = with(density) { maxHeight.toPx() }
        // Enough on each side to fill the wheel, and one more to scroll in.
        val reach = ceil(heightPx / 2f / spacingPx).toInt() + 1
        // Wrapping a handful round would show the same few over and over; the
        // wheel wraps once it is longer than it is tall, as StepMania's is.
        // Counted on the whole height, so the glass coming and going never
        // turns wrapping on or off under the player's thumb.
        val wraps = count > reach * 2
        val wrapsNow = rememberUpdatedState(wraps)
        fun clamp(value: Float): Float =
            if (wrapsNow.value) value else value.coerceIn(0f, (currentItems.value.size - 1).toFloat())
        fun indexAt(slot: Int): Int? {
            val size = currentItems.value.size
            return when {
                size == 0 -> null
                wrapsNow.value -> ((slot % size) + size) % size
                slot in 0 until size -> slot
                else -> null
            }
        }
        // Wrapping turned off (a rotation): back into range, on the same item.
        LaunchedEffect(wraps) {
            if (!wraps) {
                val p = position.floatValue
                position.floatValue = ((p % count) + count) % count
            }
        }

        // Where the centre sits: the middle of what the glass leaves, never so
        // far that the centre item leaves the wheel.
        fun shiftPx(): Float {
            val raw = with(density) { (topNow() - bottomNow()).toPx() } / 2f
            val limit = (heightPx / 2f - spacingPx / 2f).coerceAtLeast(0f)
            return raw.coerceIn(-limit, limit)
        }
        // Extra items to compose on the far side of a moved centre; changes a
        // few times while the glass animates, not every frame.
        val extra by remember(spacingPx, heightPx) { derivedStateOf { ceil(abs(shiftPx()) / spacingPx).toInt() } }

        // Turns, in pixels: a drag, a fling, a settle. Short of the ends of a
        // wheel that does not wrap, the rest goes on to the page.
        val scrollState = rememberScrollableState { delta ->
            val pos = positionNow.value
            val before = pos.floatValue
            val after = clamp(before - delta / spacingPx)
            pos.floatValue = after
            -(after - before) * spacingPx
        }
        var target by remember { mutableFloatStateOf(Float.NaN) }
        var speed by remember { mutableFloatStateOf(0f) }
        var settling by remember { mutableStateOf<Job?>(null) }
        // A new set of items: whatever was turning stops, so it cannot carry
        // the new set off its anchor.
        DisposableEffect(items) {
            onDispose {
                settling?.cancel()
                scope.launch { scrollState.stopScroll(MutatePriority.UserInput) }
            }
        }

        suspend fun ScrollScope.glide(to: Float, velocity: Float) {
            target = to
            var last = positionNow.value.floatValue
            animate(last, to, velocity, SETTLE) { value, v ->
                scrollBy(-(value - last) * spacingPx)
                last = value
                speed = v
            }
        }
        val fling = remember(spacingPx) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val velocity = -initialVelocity / spacingPx
                    // Where a fling would coast to, settled on the item there.
                    val coast = exponentialDecay<Float>(frictionMultiplier = FRICTION)
                        .calculateTargetValue(positionNow.value.floatValue, velocity)
                    glide(clamp(coast.roundToInt().toFloat()), velocity)
                    return 0f
                }
            }
        }
        fun settleTo(to: Float) {
            // Carried on from a turn already under way, so presses in quick
            // succession add up and keep their speed.
            val carry = if (settling?.isActive == true) speed else 0f
            settling = scope.launch {
                scrollState.scroll(MutatePriority.Default) { glide(clamp(to), carry) }
            }
        }
        fun step(by: Int) {
            val base = if (settling?.isActive == true && !target.isNaN()) {
                target
            } else {
                positionNow.value.floatValue.roundToInt().toFloat()
            }
            settleTo(base + by)
        }

        val centre by remember { derivedStateOf { positionNow.value.floatValue.roundToInt() } }
        fun activateCentre() {
            indexAt(centre)?.let { activateNow(currentItems.value[it]) }
        }

        // The centred item, each time it is another one — by identity, not by
        // place: a new list with a different song at the same place is a new
        // centre. A turn the player made swishes; the wheel being set to a new
        // place by a sort, a search or a folder does not.
        LaunchedEffect(Unit) {
            snapshotFlow { indexAt(positionNow.value.floatValue.roundToInt())?.let { currentItems.value[it] } }
                .distinctUntilChangedBy { it?.key }
                .collect { item ->
                    if (item == null) return@collect
                    if (soundOn && scrollState.isScrollInProgress) swish.swish(System.nanoTime())
                    focusNow(item)
                }
        }

        val focus = remember { FocusRequester() }
        LaunchedEffect(requestFocus) { if (requestFocus) runCatching { focus.requestFocus() } }
        val centred = indexAt(centre)?.let { items[it] }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .scrollable(scrollState, Orientation.Vertical, flingBehavior = fling)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { step(-1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { step(1); true }
                        KeyEvent.KEYCODE_PAGE_UP -> { step(-PAGE); true }
                        KeyEvent.KEYCODE_PAGE_DOWN -> { step(PAGE); true }
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> { activateCentre(); true }
                        // Left and Right go to focus search, out to the panel
                        // and the top bar: a wheel that kept all four was a
                        // trap for a D-pad.
                        else -> false
                    }
                }
                .focusRequester(focus)
                .focusable()
                .semantics {
                    // Says what is centred, and again as it changes, so a turn
                    // by Next or Previous is heard.
                    contentDescription = centred?.let { "Music wheel, ${describe(it)}" } ?: "Music wheel"
                    liveRegion = LiveRegionMode.Polite
                    customActions = listOf(
                        CustomAccessibilityAction("Previous") { step(-1); true },
                        CustomAccessibilityAction("Next") { step(1); true },
                    )
                },
        ) {
            for (slot in centre - reach - extra..centre + reach + extra) {
                val index = indexAt(slot) ?: continue
                val item = items[index]
                key(slot, item.key) {
                    WheelEntry(
                        item = item,
                        centred = slot == centre,
                        selected = item is GlyphWheel.Item.Song && item.song.trackId == selectedTrackId,
                        locked = foldersLocked,
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
                                // and the glass moving shift layers and
                                // recompose nothing.
                                val offset = slot - positionNow.value.floatValue
                                val distance = abs(offset)
                                translationY = offset * spacingPx + shiftPx()
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

/** What an item is, in words: the wheel's centre, and each item's own description. */
private fun describe(item: GlyphWheel.Item): String = when (item) {
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

/** One item on the wheel: a folder, or a song inside the open one. */
@Composable
private fun WheelEntry(
    item: GlyphWheel.Item,
    centred: Boolean,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val description = describe(item)
    val label = when {
        !centred -> if (item is GlyphWheel.Item.Song) "Choose" else "Bring to the centre"
        item is GlyphWheel.Item.Folder -> when {
            item.open && locked -> null
            item.open -> "Close folder"
            else -> "Open folder"
        }
        else -> "Choose"
    }
    Surface(
        modifier = modifier
            .padding(horizontal = MonoDimens.listItemPaddingH)
            // The wheel is the keyboard's one stop; its keys turn and open it.
            .focusProperties { canFocus = false }
            // The rows' press: a squeeze, not a ripple.
            .bounceClick(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                // Spoken by the framework, never left to the tint.
                this.selected = selected
                if (centred) stateDescription = "In the centre"
                if (label != null) onClick(label = label) { onClick(); true }
            }
            .materialSurface(shape = MonoDimens.shapeMd),
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
                fontWeight = if (centred) FontWeight.Medium else FontWeight.SemiBold,
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
                fontWeight = if (centred) FontWeight.Medium else FontWeight.Normal,
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
private val NO_INSET: () -> Dp = { 0.dp }
/** StepMania's switch between items: quick, barely past its mark. */
private val SETTLE = spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
