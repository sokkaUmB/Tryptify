package tf.monochrome.android.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.log2
import kotlin.math.roundToInt
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqTarget
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.ui.mixer.GlassChoiceChip

/*
 * Precision AutoEQ's controls under its graph: the targets as pills, a chip
 * per filter, and the selected filter's type, frequency, gain and Q as scrub
 * pills, with a dialog to type a value in. They began on the EQ Studio screen,
 * which Precision AutoEQ replaced.
 */

/** Green, the target's colour on the graph and on its pills, in both themes. */
internal val TargetColor = Color(0xFF30A46C)

/** How an imported target's id starts (EqViewModel.importCustomTarget). */
private const val CUSTOM_TARGET_PREFIX = "custom_"

/** Which value the exact-entry dialog is open for. */
internal enum class StudioField { FREQ, GAIN, Q, PREAMP }

/** Typing a value in: the selected filter's frequency, gain or Q, or the preamp. */
@Composable
internal fun StudioFieldDialog(field: StudioField, band: EqBand?, viewModel: EqViewModel, onDismiss: () -> Unit) {
    val (title, initial) = when (field) {
        StudioField.FREQ -> stringResource(R.string.eq_studio_freq) to (band?.freq?.roundToInt()?.toString() ?: "")
        StudioField.GAIN -> stringResource(R.string.eq_studio_gain) to (band?.let { String.format(java.util.Locale.US, "%.1f", it.gain) } ?: "")
        StudioField.Q -> stringResource(R.string.eq_studio_q) to (band?.let { formatStudioQ(it.q) } ?: "")
        StudioField.PREAMP -> stringResource(R.string.eq_preamp) to String.format(java.util.Locale.US, "%.1f", viewModel.currentPreamp.value)
    }
    StudioValueDialog(
        title = title,
        initial = initial,
        onDismiss = onDismiss,
        onConfirm = { v ->
            when (field) {
                StudioField.FREQ -> band?.let { viewModel.updateBand(it.id, it.copy(freq = v.coerceIn(StudioAxis.MIN_FREQ, StudioAxis.MAX_FREQ))) }
                StudioField.GAIN -> band?.let { viewModel.updateBand(it.id, it.copy(gain = StudioBands.clampGain(v))) }
                StudioField.Q -> band?.let { viewModel.updateBand(it.id, it.copy(q = v.coerceIn(StudioBands.MIN_Q, StudioBands.MAX_Q))) }
                StudioField.PREAMP -> viewModel.setPreamp(v)
            }
        },
    )
}

/**
 * Every target one tap away, under the graph, as peqdb has them: comparing two
 * targets is the most common thing done here, and the classic screen hid them
 * behind a dropdown.
 */
@Composable
internal fun TargetStrip(
    targets: List<EqTarget>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    /** Removes an imported target; offered on the one selected, as a small × on its pill. */
    onDelete: ((String) -> Unit)? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(targets, key = { it.id }) { target ->
            val deletable = onDelete != null && target.id == selectedId && target.id.startsWith(CUSTOM_TARGET_PREFIX)
            val deleteLabel = stringResource(R.string.eq_delete_target, target.label)
            GlassChoiceChip(
                label = target.label,
                selected = target.id == selectedId,
                accent = TargetColor,
                onClick = { onSelect(target.id) },
                trailingContent = if (!deletable) null else {
                    {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = deleteLabel,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable { onDelete?.invoke(target.id) },
                        )
                    }
                },
            )
        }
        item(key = "import") {
            GlassChoiceChip(
                label = stringResource(R.string.eq_studio_import_target),
                selected = false,
                accent = TargetColor,
                leadingIcon = Icons.Default.Add,
                description = stringResource(R.string.eq_import_custom_target),
                onClick = onImport,
            )
        }
    }
}

/** Every filter as a chip in its own colour, numbered as on the graph; a tap selects it. */
@Composable
internal fun FilterChipsRow(
    bands: List<EqBand>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = contentPadding) {
        itemsIndexed(bands, key = { _, b -> b.id }) { index, band ->
            val color = studioFilterColor(band.id)
            GlassChoiceChip(
                label = "${index + 1} · ${band.type.studioCode()} ${formatStudioFreq(band.freq)}",
                selected = band.id == selectedId,
                accent = color,
                onClick = { onSelect(band.id) },
                leadingContent = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (band.enabled) color else color.copy(alpha = 0.3f)),
                    )
                },
            )
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** The selected filter: its type, its three values as scrub pills, on/off and delete. */
@Composable
internal fun FilterInspector(
    band: EqBand,
    number: Int,
    onChange: (EqBand) -> Unit,
    onDelete: () -> Unit,
    onType: (StudioField) -> Unit,
) {
    val color = studioFilterColor(band.id)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.eq_studio_filter_n, number),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            GlassChoiceChip(
                label = stringResource(if (band.enabled) R.string.eq_studio_on else R.string.eq_studio_bypassed),
                selected = band.enabled,
                accent = color,
                onClick = { onChange(band.copy(enabled = !band.enabled)) },
            )
            Spacer(Modifier.width(8.dp))
            GlassChoiceChip(
                label = stringResource(R.string.eq_studio_delete),
                selected = false,
                accent = MaterialTheme.colorScheme.error,
                leadingIcon = Icons.Default.DeleteOutline,
                onClick = onDelete,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Filter types stay in English in every language, as on the classic
            // band editor and in every DAW (app/l10n/GLOSSARY.md).
            for ((type, label) in listOf(
                FilterType.LOWSHELF to "LOW-S",
                FilterType.PEAKING to "PEAK",
                FilterType.HIGHSHELF to "HIGH-S",
            )) {
                GlassChoiceChip(
                    label = label,
                    selected = band.type == type,
                    accent = color,
                    onClick = { onChange(band.copy(type = type)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BandScrubPills(band = band, accent = color, onChange = onChange, onType = onType)
    }
}

/**
 * Frequency, gain and Q as scrub pills. The scales are chosen so a thumb-width
 * swipe is a musically sized move: a third of an octave, about a dB, a quarter
 * of an octave of Q. Each pill ticks at its coarse step.
 */
@Composable
private fun BandScrubPills(
    band: EqBand,
    accent: Color,
    onChange: (EqBand) -> Unit,
    onType: (StudioField) -> Unit,
) {
    var startFreq by remember { mutableFloatStateOf(band.freq) }
    var startGain by remember { mutableFloatStateOf(band.gain) }
    var startQ by remember { mutableFloatStateOf(band.q) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScrubPill(
            label = stringResource(R.string.eq_studio_freq),
            value = formatStudioFreq(band.freq),
            tick = (log2(band.freq) * 6f).roundToInt(),
            accent = accent,
            onDragStart = { startFreq = band.freq },
            onDrag = { dp -> onChange(band.copy(freq = StudioBands.roundFreq(StudioBands.scrubFreq(startFreq, dp / 60f)))) },
            onTap = { onType(StudioField.FREQ) },
            modifier = Modifier.weight(1f),
        )
        ScrubPill(
            label = stringResource(R.string.eq_studio_gain),
            value = formatStudioGain(band.gain),
            tick = band.gain.roundToInt(),
            accent = accent,
            onDragStart = { startGain = band.gain },
            onDrag = { dp -> onChange(band.copy(gain = StudioBands.clampGain(((startGain + dp / 16f) * 10f).roundToInt() / 10f))) },
            onTap = { onType(StudioField.GAIN) },
            modifier = Modifier.weight(1f),
        )
        ScrubPill(
            label = stringResource(R.string.eq_studio_q),
            value = formatStudioQ(band.q),
            tick = (log2(band.q) * 4f).roundToInt(),
            accent = accent,
            onDragStart = { startQ = band.q },
            onDrag = { dp -> onChange(band.copy(q = (StudioBands.scrubQ(startQ, dp / 80f) * 100f).roundToInt() / 100f)) },
            onTap = { onType(StudioField.Q) },
            modifier = Modifier.weight(1f),
        )
    }
}
