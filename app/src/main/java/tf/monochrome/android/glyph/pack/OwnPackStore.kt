// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tf.monochrome.android.glyph.data.GlyphSong

/**
 * The charts made in the app, kept as a StepMania pack of their own — the
 * [StepManiaPack.OWN_PACK] — in the folder the player chose.
 *
 * Every chart generated is written there as it finishes
 * (`<folder>/Tryptify/<song>/<song>.ssc` with its music beside it); the whole
 * set can be written again ([saveAll]), or as one ZIP to share ([zip]) that
 * imports, here or anywhere, like a pack from StepMania Online.
 */
@Singleton
class OwnPackStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("trypt_mania_packs", Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(readFolder())
    /** The chosen folder, or null when none is chosen or its grant is gone. */
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    /** Whether the player has already been asked, and said not now. */
    var declined: Boolean
        get() = prefs.getBoolean(KEY_DECLINED, false)
        set(value) = prefs.edit { putBoolean(KEY_DECLINED, value) }

    fun choose(tree: Uri) {
        runCatching { TreePackSink.keep(context, tree) }
        prefs.edit {
            putString(KEY_FOLDER, tree.toString())
            putBoolean(KEY_DECLINED, false)
        }
        _folder.value = tree
    }

    fun forget() {
        _folder.value?.let { tree ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    tree,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit { remove(KEY_FOLDER) }
        _folder.value = null
    }

    /**
     * Write every made chart in [songs] into the chosen folder's pack.
     * Returns how many were written; [onSong] is told each one's title.
     */
    suspend fun saveAll(songs: List<OwnSong>, onSong: (Int, String) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val tree = _folder.value ?: return@withContext 0
        write(TreePackSink(context, tree), songs, onSong)
    }

    /** Write every made chart in [songs] as one pack ZIP into [destination]. */
    suspend fun zip(destination: Uri, songs: List<OwnSong>, onSong: (Int, String) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw java.io.IOException("Could not write the ZIP.")
        ZipPackSink(output).use { write(it, songs, onSong) }
    }

    private fun write(sink: PackSink, songs: List<OwnSong>, onSong: (Int, String) -> Unit): Int {
        var written = 0
        for ((index, song) in songs.withIndex()) {
            onSong(index, song.title)
            val music = File(song.musicPath).takeIf { it.isFile }
            PackWriter.writeSong(
                sink = sink,
                pack = StepManiaPack.OWN_PACK,
                title = song.title,
                artist = song.artist,
                ssc = song.chart.readText(),
                music = music?.let { file -> { file.inputStream() } },
            )
            written += 1
        }
        return written
    }

    private fun readFolder(): Uri? = prefs.getString(KEY_FOLDER, null)
        ?.let(Uri::parse)
        ?.takeIf { TreePackSink.isUsable(context, it) }

    /** One chart the app made: the song, its chart file and its music. */
    data class OwnSong(val title: String, val artist: String, val chart: File, val musicPath: String) {
        companion object {
            fun of(song: GlyphSong, chart: File) = OwnSong(song.title, song.artist, chart, song.filePath)
        }
    }

    private companion object {
        const val KEY_FOLDER = "own_pack_folder"
        const val KEY_DECLINED = "own_pack_declined"
    }
}
