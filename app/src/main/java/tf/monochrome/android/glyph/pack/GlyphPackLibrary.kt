// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong

/**
 * The StepMania packs imported into the app, as songs the mode can play.
 *
 * Packs live in `files/glyph/packs/<pack>/<song>/`, unpacked by
 * [PackImporter], which also wrote each pack's index — so listing a hundred
 * songs reads a hundred small JSON files' worth, not a hundred charts.
 *
 * A pack song's id is `pack:<pack>/<song folder>`. Its chart is the pack's
 * own, read in place; once edited in the map editor, the edit is saved as the
 * app's own copy and wins, and the pack's file is never written to.
 *
 * A pack read in place (songs already unpacked on the phone, linked rather
 * than copied) keeps only its index and charts here; its music and art are
 * documents in the folder the player picked, read through the grant kept for
 * it, and removing the pack gives the grant back.
 */
@Singleton
class GlyphPackLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One imported pack, for the list under Settings. */
    data class Pack(val name: String, val songs: Int, val bytes: Long, val inPlace: Boolean = false)

    val root: File get() = File(context.filesDir, "glyph/packs")

    private val _songs = MutableStateFlow<List<GlyphSong>>(emptyList())
    val songs: StateFlow<List<GlyphSong>> = _songs.asStateFlow()

    private val _packs = MutableStateFlow<List<Pack>>(emptyList())
    val packs: StateFlow<List<Pack>> = _packs.asStateFlow()

    @Volatile private var loaded = false

    /** An importer writing into this library, measuring each song's length as it goes. */
    fun importer(): PackImporter = PackImporter(root, durationOf = ::durationOf, durationOfUri = ::durationOfUri)

    /** Re-read every pack's index. Cheap; called after an import or a removal. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val folders = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        val songs = ArrayList<GlyphSong>()
        val packs = ArrayList<Pack>()
        for (folder in folders.sortedBy { it.name.lowercase() }) {
            val index = PackImporter.readIndex(folder) ?: continue
            packs += Pack(
                name = index.name,
                songs = index.songs.size,
                bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                inPlace = index.source != null,
            )
            for (song in index.songs) songs += song.toGlyphSong(folder, index.name)
        }
        _songs.value = songs
        _packs.value = packs
        loaded = true
    }

    /** Load once, the first time anything asks. */
    suspend fun ensureLoaded() {
        if (!loaded) refresh()
    }

    /** Delete an imported pack and everything in it. */
    suspend fun remove(name: String) {
        withContext(Dispatchers.IO) {
            val folder = File(root, name)
            if (!folder.canonicalPath.startsWith(root.canonicalPath + File.separator)) return@withContext
            val source = PackImporter.readIndex(folder)?.source
            folder.deleteRecursively()
            // The folder's grant goes back once no pack is read from it.
            if (source != null) {
                val stillUsed = root.listFiles()?.any { PackImporter.readIndex(it)?.source == source } == true
                if (!stillUsed) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(source),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
        refresh()
    }

    /** The pack's own chart for a pack song's [trackId], or null for any other song. */
    fun chartFile(trackId: String): File? {
        val (pack, song) = parse(trackId) ?: return null
        val folder = File(root, pack)
        val entry = PackImporter.readIndex(folder)?.songs?.firstOrNull { it.folder == song } ?: return null
        return File(folder, "$song/${entry.chart}")
    }

    private fun PackIndex.Song.toGlyphSong(packFolder: File, packName: String): GlyphSong {
        val songFolder = File(packFolder, folder)
        val musicFile = File(songFolder, music)
        val levels = difficulties.mapNotNull { name -> StepManiaDifficulty.entries.firstOrNull { it.name == name } }
        val id = "$PREFIX${packFolder.name}/$folder"
        return GlyphSong(
            trackId = id,
            chartId = id.hashCode().toUInt().toString(16),
            title = title,
            artist = artist,
            // Read in place: the document in the player's folder.
            filePath = musicUri ?: musicFile.absolutePath,
            artworkUri = artUri ?: art?.let { Uri.fromFile(File(songFolder, it)).toString() },
            durationSeconds = durationSeconds,
            codec = codecOf(music),
            bpm = bpm,
            difficulties = levels,
            chartState = if (levels.isNotEmpty()) GlyphChartState.READY else GlyphChartState.UNREADABLE,
            pack = packName,
        )
    }

    private fun durationOf(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun durationOfUri(uri: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val PREFIX = "pack:"

        fun isPackSong(trackId: String): Boolean = trackId.startsWith(PREFIX)

        private fun parse(trackId: String): Pair<String, String>? {
            if (!isPackSong(trackId)) return null
            val rest = trackId.removePrefix(PREFIX)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            return rest.substring(0, slash) to rest.substring(slash + 1)
        }

        private fun codecOf(name: String): AudioCodec = when (StepManiaPack.extension(name)) {
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "mp3" -> AudioCodec.MP3
            "flac" -> AudioCodec.FLAC
            "wav" -> AudioCodec.WAV
            "m4a" -> AudioCodec.AAC
            else -> AudioCodec.UNKNOWN
        }
    }
}
