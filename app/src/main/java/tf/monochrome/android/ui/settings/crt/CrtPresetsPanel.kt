// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.settings.crt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.glyph.engine.CrtPreset
import tf.monochrome.android.glyph.engine.CrtPresets
import tf.monochrome.android.glyph.engine.GlyphTvShader
import tf.monochrome.android.glyph.engine.GlyphTvTuning
import tf.monochrome.android.ui.mixer.GlassChoiceChip

/**
 * CRT presets: the four built-in looks and the user's own as chips, then
 * Save (the look on screen, under a name), Import (a `.crt.json` file) and
 * Export (the look on screen, to a file). A chip shows as picked when the
 * look on screen is exactly it; one of the user's, picked, can be deleted.
 *
 * Shown wherever the CRT is set: the app-wide effect (Settings and the Lyrics
 * FX tab) and Trypt Mania's Graphics. [onApply] sets the look and its
 * sliders together, which also turns the effect on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrtPresetsPanel(
    shader: GlyphTvShader,
    tuning: GlyphTvTuning,
    onApply: (GlyphTvShader, GlyphTvTuning) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrtPresetsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val height = LocalWindowInfo.current.containerSize.height
    val builtIns = remember(height) { CrtPresets.builtIns(height) }
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var naming by rememberSaveable { mutableStateOf(false) }

    val builtInName: (CrtPreset) -> String = { preset ->
        when (preset.builtInId) {
            CrtPresets.ARCADE -> context.getString(R.string.crt_preset_arcade)
            CrtPresets.TRINITRON -> context.getString(R.string.crt_preset_trinitron)
            CrtPresets.LIVING_ROOM -> context.getString(R.string.crt_preset_living_room)
            CrtPresets.VHS -> context.getString(R.string.crt_preset_vhs)
            else -> preset.name
        }
    }
    val on = shader != GlyphTvShader.OFF
    val pickedBuiltIn = builtIns.firstOrNull { on && it.matches(shader, tuning) }
    val pickedSaved = saved.firstOrNull { on && it.matches(shader, tuning) }
    // What Export writes: the look on screen, under the name of the preset it is, if any.
    val current = CrtPreset(
        name = pickedSaved?.name ?: pickedBuiltIn?.let(builtInName) ?: context.getString(R.string.crt_preset_default_name),
        shader = shader,
        tuning = tuning,
    )

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.export(
                current,
                uri,
                done = context.getString(R.string.crt_preset_exported, current.name),
                failed = context.getString(R.string.crt_preset_export_failed),
            )
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.import(
                uri,
                apply = { onApply(it.shader, it.tuning) },
                failed = { why -> context.getString(R.string.crt_preset_import_failed, why) },
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.crt_presets),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.crt_presets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            builtIns.forEach { preset ->
                GlassChoiceChip(
                    label = builtInName(preset),
                    selected = preset == pickedBuiltIn,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onApply(preset.shader, preset.tuning) },
                )
            }
            saved.forEach { preset ->
                GlassChoiceChip(
                    label = preset.name,
                    selected = preset == pickedSaved,
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = { onApply(preset.shader, preset.tuning) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { naming = true }, enabled = on, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(
                onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.crt_preset_import))
            }
            OutlinedButton(
                onClick = { exporter.launch(CrtPresets.fileName(current.name)) },
                enabled = on,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.crt_preset_export))
            }
        }
        pickedSaved?.let { preset ->
            TextButton(onClick = { viewModel.delete(preset) }) {
                Text(stringResource(R.string.crt_preset_delete, preset.name), color = MaterialTheme.colorScheme.error)
            }
        }
        message?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (naming) {
        var name by rememberSaveable { mutableStateOf(pickedSaved?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.crt_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CrtPresets.MAX_NAME) },
                    label = { Text(stringResource(R.string.crt_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.save(CrtPreset(name.trim(), shader, tuning.clamped()))
                        viewModel.clearMessage()
                        naming = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
