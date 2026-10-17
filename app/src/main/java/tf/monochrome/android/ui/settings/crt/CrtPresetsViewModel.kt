// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.settings.crt

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.glyph.engine.CrtPreset
import tf.monochrome.android.glyph.engine.CrtPresets

/** The presets panel's saving, importing and exporting, off the main thread. */
@HiltViewModel
class CrtPresetsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: CrtPresetStore,
) : ViewModel() {

    val saved: StateFlow<List<CrtPreset>> = store.presets

    /** One line for the panel to show once: what an import or export did. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    fun save(preset: CrtPreset) {
        viewModelScope.launch { store.save(preset) }
    }

    fun delete(preset: CrtPreset) {
        viewModelScope.launch { store.delete(preset.name) }
    }

    /** Writes [preset] to [uri], a file the user picked to save it as. */
    fun export(preset: CrtPreset, uri: Uri, done: String, failed: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(CrtPresets.export(preset).toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }.isSuccess
            }
            _message.value = if (ok) done else failed
        }
    }

    /**
     * Reads the preset file at [uri], keeps it with the user's presets and
     * hands it to [apply]; or says why it could not.
     */
    fun import(uri: Uri, apply: (CrtPreset) -> Unit, failed: (String) -> String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // A preset is a few hundred bytes; anything big is not one.
                        String(stream.readNBytesCompat(MAX_FILE_BYTES), Charsets.UTF_8)
                    } ?: error("could not open the file")
                }.mapCatching { CrtPresets.import(it).getOrThrow() }
            }
            result
                .onSuccess { preset ->
                    store.save(preset)
                    apply(preset)
                    _message.value = null
                }
                .onFailure { _message.value = failed(it.message ?: "unreadable") }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024
    }
}
