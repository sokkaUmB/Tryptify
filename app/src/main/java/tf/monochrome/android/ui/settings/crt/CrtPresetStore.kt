// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.settings.crt

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.glyph.engine.CrtPreset
import tf.monochrome.android.glyph.engine.CrtPresets

/**
 * The CRT presets the user saved or imported, one file in the app's storage.
 * One store for the three places the CRT is set (Settings, the Lyrics FX
 * tab, Trypt Mania), so a look saved in one is offered in the others.
 */
@Singleton
class CrtPresetStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val lock = Mutex()
    private val _presets = MutableStateFlow(read())
    val presets: StateFlow<List<CrtPreset>> = _presets.asStateFlow()

    /** Keeps [preset]; one of the same name is replaced, in its place. */
    suspend fun save(preset: CrtPreset) = update { list ->
        val at = list.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        if (at >= 0) list.toMutableList().also { it[at] = preset } else list + preset
    }

    suspend fun delete(name: String) = update { list -> list.filterNot { it.name == name } }

    private suspend fun update(change: (List<CrtPreset>) -> List<CrtPreset>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = change(_presets.value)
            runCatching { file.writeText(CrtPresets.encodeAll(next)) }
                .onFailure { Log.w(TAG, "could not save CRT presets: ${it.message}") }
            _presets.value = next
        }
    }

    private fun read(): List<CrtPreset> =
        CrtPresets.decodeAll(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    private companion object {
        const val TAG = "CrtPresetStore"
        const val FILE_NAME = "crt_presets.json"
    }
}
