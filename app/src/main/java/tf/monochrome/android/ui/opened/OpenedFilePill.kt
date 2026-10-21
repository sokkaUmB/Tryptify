package tf.monochrome.android.ui.opened

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import tf.monochrome.android.data.opened.OpenedAudioRepository.Added
import tf.monochrome.android.data.opened.OpenedAudioRepository.Failure
import tf.monochrome.android.data.opened.OpenedAudioRepository.Opened
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.components.GlassPill

/**
 * A song opened from another app, as a glass pill over whatever screen is
 * open: "Opened from another app", its title and Add to library (a tap
 * anywhere on the pill adds it). A file that could not be opened says why
 * instead. Neither shows for a song that is already in the library: it just
 * plays.
 *
 * [opened] is handed to [onTake] as soon as it arrives, which is what starts
 * it playing; the pill only reports. Chrome over the app, so the mini
 * player's material and a sibling of the app's haze source, as the
 * achievement pill is.
 */
@Composable
fun OpenedFilePill(
    opened: Opened?,
    onTake: (Opened) -> Unit,
    onAdd: (UnifiedTrack, (Added) -> Unit) -> Unit,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    var showing by remember { mutableStateOf<Opened?>(null) }
    var stage by remember { mutableStateOf(Stage.OFFERED) }
    var added by remember { mutableStateOf<Added?>(null) }
    var visible by remember { mutableStateOf(false) }
    // Bumped on each change the pill should be held for, restarting its timer.
    var hold by remember { mutableIntStateOf(0) }

    LaunchedEffect(opened) {
        val item = opened ?: return@LaunchedEffect
        onTake(item)
        if (item is Opened.Ready && item.inLibrary) return@LaunchedEffect
        showing = item
        stage = Stage.OFFERED
        added = null
        visible = true
        hold++
    }
    LaunchedEffect(hold) {
        if (hold == 0) return@LaunchedEffect
        delay(
            when {
                stage == Stage.ADDING -> return@LaunchedEffect
                stage == Stage.DONE -> DONE_MILLIS
                showing is Opened.Failed -> FAILED_MILLIS
                else -> OFFER_MILLIS
            },
        )
        visible = false
    }

    val enter = if (reducedMotion) fadeIn() else slideInVertically(tween(420)) { -it * 2 } + fadeIn(tween(200))
    val exit = if (reducedMotion) fadeOut() else slideOutVertically(tween(320)) { -it * 2 } + fadeOut(tween(320))
    AnimatedVisibility(
        visible = visible && showing != null,
        enter = enter,
        exit = exit,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val item = showing ?: return@AnimatedVisibility
        val ready = item as? Opened.Ready
        val result = added
        val (label, line) = when {
            item is Opened.Failed ->
                stringResource(R.string.opened_failed) to stringResource(reasonFor(item.failure))
            result is Added.Saved ->
                stringResource(R.string.opened_added) to stringResource(R.string.opened_added_where, result.folder)
            result is Added.Failed ->
                stringResource(R.string.opened_add_failed) to stringResource(reasonFor(result.failure))
            else -> stringResource(R.string.opened_from_app) to (ready?.track?.title.orEmpty())
        }
        val canAdd = ready != null && stage == Stage.OFFERED
        val addLabel = stringResource(R.string.opened_add)
        GlassPill(
            onClick = {
                if (canAdd) {
                    stage = Stage.ADDING
                    hold++
                    onAdd(ready!!.track) { outcome ->
                        added = outcome
                        stage = Stage.DONE
                        hold++
                    }
                } else {
                    visible = false
                }
            },
            hazeState = hazeState,
            glass = glass,
            height = 72.dp,
            onClickLabel = if (canAdd) addLabel else null,
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
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = when {
                            item is Opened.Failed || result is Added.Failed -> Icons.Default.ErrorOutline
                            result is Added.Saved -> Icons.Default.CheckCircle
                            else -> Icons.Default.MusicNote
                        },
                        contentDescription = null,
                        tint = if (item is Opened.Failed || result is Added.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = line,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    canAdd -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) {
                        Text(
                            text = addLabel,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    stage == Stage.ADDING -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** The line under "Couldn't open this file" or "Couldn't add it". */
fun reasonFor(failure: Failure): Int = when (failure) {
    Failure.NOT_AUDIO -> R.string.opened_fail_not_audio
    Failure.NO_ACCESS -> R.string.opened_fail_no_access
    Failure.NOT_FOUND -> R.string.opened_fail_not_found
    Failure.TOO_BIG -> R.string.opened_fail_too_big
    Failure.EMPTY -> R.string.opened_fail_empty
    Failure.NO_SPACE -> R.string.opened_fail_no_space
    Failure.UNREADABLE -> R.string.opened_fail_unreadable
    Failure.NO_WRITE -> R.string.opened_fail_no_write
}

private enum class Stage { OFFERED, ADDING, DONE }

/** Long enough to read the title and reach for Add. */
private const val OFFER_MILLIS = 8_000L
private const val FAILED_MILLIS = 6_000L
private const val DONE_MILLIS = 3_000L
