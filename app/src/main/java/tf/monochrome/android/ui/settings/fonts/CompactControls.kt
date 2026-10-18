package tf.monochrome.android.ui.settings.fonts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row of equal steps, one chosen: the Fonts page's text size. Small enough
 * to sit beside its label on one line, which a row of chips was not.
 */
@Composable
internal fun CompactSegmented(
    labels: List<String>,
    /** What TalkBack says for each step, where the label is a number. */
    descriptions: List<String>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(2.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) scheme.surface else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(i) }
                    .semantics { contentDescription = descriptions.getOrElse(i) { label } },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * "− 15 +": a value stepped in place, for the typography table's rows, where a
 * slider per field would not fit. [changed] draws the value in the accent.
 */
@Composable
internal fun CompactStepper(
    value: String,
    changed: Boolean,
    lessLabel: String,
    moreLabel: String,
    onLess: (() -> Unit)?,
    onMore: (() -> Unit)?,
    width: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(width)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.6f)),
    ) {
        StepButton("−", lessLabel, onLess)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = if (changed) scheme.primary else scheme.onSurface,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", moreLabel, onMore)
    }
}

@Composable
private fun StepButton(symbol: String, label: String, onClick: (() -> Unit)?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(24.dp)
            .fillMaxHeight()
            .clickable(enabled = onClick != null, onClickLabel = label) { onClick?.invoke() }
            .semantics { contentDescription = label },
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.labelLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** A field's label in the table's header, small and spaced. */
@Composable
internal fun ColumnLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}
