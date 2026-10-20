package tf.monochrome.android.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassPill

/**
 * The achievement just completed, as a glass pill dropping in over whatever
 * screen is open: its icon in a gold ring that fills, "Achievement unlocked"
 * and its name, and View, which opens it on the Stats screen (a tap anywhere
 * on the pill does). It holds [HOLD_MILLIS] and slides away; the next one
 * waiting follows.
 *
 * Chrome over the app, so the mini player's material, and a sibling of the
 * app's haze source, as the DAC volume popup is. Under reduced motion it fades
 * rather than slides and the ring is drawn full.
 */
@Composable
fun AchievementUnlockedPill(
    pending: List<Achievement>,
    onShown: (Achievement) -> Unit,
    onView: () -> Unit,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val next = pending.firstOrNull()
    // The one on screen, kept while it slides out after it has been taken.
    var showing by remember { mutableStateOf<Achievement?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(next) {
        if (next == null || showing != null) return@LaunchedEffect
        showing = next
        visible = true
        delay(HOLD_MILLIS)
        visible = false
        delay(EXIT_MILLIS)
        onShown(next)
        showing = null
    }

    val enter = if (reducedMotion) fadeIn() else slideInVertically(tween(420)) { -it * 2 } + fadeIn(tween(200))
    val exit = if (reducedMotion) fadeOut() else slideOutVertically(tween(EXIT_MILLIS.toInt())) { -it * 2 } + fadeOut(tween(EXIT_MILLIS.toInt()))
    AnimatedVisibility(
        visible = visible && showing != null,
        enter = enter,
        exit = exit,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val achievement = showing ?: return@AnimatedVisibility
        val copy = copyFor(achievement)
        val label = stringResource(R.string.stats_ach_unlocked_toast)
        val title = stringResource(copy.title)
        GlassPill(
            onClick = {
                visible = false
                onView()
            },
            hazeState = hazeState,
            glass = glass,
            height = 72.dp,
            onClickLabel = stringResource(R.string.stats_ach_view),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp),
            ) {
                FillingRing(icon = copy.icon, reducedMotion = reducedMotion)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold,
                        // The accent, which the glass moves until it reads
                        // (GlassInkScope): gold text failed on light glass.
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The pill is the button; this says what it does.
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) {
                    Text(
                        text = stringResource(R.string.stats_ach_view),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** The achievement's icon in a gold ring that draws itself round once. */
@Composable
private fun FillingRing(icon: androidx.compose.ui.graphics.vector.ImageVector, reducedMotion: Boolean) {
    val sweep = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            delay(300)
            sweep.animateTo(1f, tween(650))
        }
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(Gold, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(22.dp))
    }
}

/** A trophy's gold, for the ring and the icon only: as text it does not read on light glass. */
private val Gold = Color(0xFFF5C451)

/** How long a pill stays up before it slides away. */
private const val HOLD_MILLIS = 4_000L

private const val EXIT_MILLIS = 320L
