// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.settings.crt

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import kotlin.math.abs

/**
 * A slider's own reset: one tap puts that slider alone back to [default].
 * Always there, so the row does not jump as it appears; dimmed and inert
 * while the slider already stands at its default.
 */
@Composable
fun SliderReset(value: Float, default: Float, label: String, onReset: () -> Unit) {
    val moved = abs(value - default) > RESET_EPSILON
    IconButton(onClick = onReset, enabled = moved, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Default.RestartAlt,
            contentDescription = stringResource(R.string.crt_reset_slider, label),
            tint = if (moved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private const val RESET_EPSILON = 1e-4f
