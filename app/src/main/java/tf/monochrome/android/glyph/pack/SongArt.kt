// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import tf.monochrome.android.data.local.coil.AudioFileCoverFetcher

/**
 * A song's cover, for a pack: what the player's own pack writes beside a
 * chart made in the app as its jacket, and what a pack song with no picture
 * of its own shows instead. A made chart names no picture, so without this a
 * song charted here came back from its pack with no cover at all.
 */
object SongArt {

    /**
     * [music]'s cover: the picture inside the audio, else the library's own
     * copy at [artworkUri] (a cached picture, never the audio again), else a
     * cover file beside a music file. Null when there is none.
     */
    fun of(context: Context, music: Uri, artworkUri: String? = null): ByteArray? =
        embedded(context, music) ?: picture(context, artworkUri) ?: besideMusic(music)

    /** The picture inside the audio at [music], a file or a document. */
    fun embedded(context: Context, music: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (music.scheme == null || music.scheme == "file") {
                retriever.setDataSource(music.path ?: return null)
            } else {
                retriever.setDataSource(context, music)
            }
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The picture at [uri], when it is a local picture rather than a song or a web address. */
    private fun picture(context: Context, uri: String?): ByteArray? {
        if (uri.isNullOrBlank()) return null
        val parsed = Uri.parse(uri)
        val name = (parsed.lastPathSegment ?: uri).substringAfterLast('/')
        if (name.substringAfterLast('.', "").lowercase() in AudioFileCoverFetcher.AUDIO_EXTENSIONS) return null
        return runCatching {
            when (parsed.scheme) {
                "content" -> context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                "file" -> parsed.path?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
                null -> File(uri).takeIf { it.isFile }?.readBytes()
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() && isPicture(it) }
    }

    /** `cover.jpg`, `folder.png` and the like beside a music file. */
    private fun besideMusic(music: Uri): ByteArray? {
        if (music.scheme != null && music.scheme != "file") return null
        val folder = music.path?.let { File(it).parentFile } ?: return null
        val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
        return COVER_NAMES.firstNotNullOfOrNull { wanted ->
            files.firstOrNull { it.nameWithoutExtension.equals(wanted, ignoreCase = true) && it.extension.lowercase() in PICTURE_EXTENSIONS }
        }?.let { runCatching { it.readBytes() }.getOrNull() }?.takeIf { isPicture(it) }
    }

    /** The name a cover is written under in a song's folder, by what it is. */
    fun jacketName(bytes: ByteArray): String = if (isPng(bytes)) "jacket.png" else "jacket.jpg"

    /**
     * [ssc] with `#JACKET` naming [name], after its `#MUSIC`; unchanged when it
     * names a jacket already, as an edited chart might.
     */
    fun withJacket(ssc: String, name: String): String {
        if (StepManiaPack.tag(ssc, "JACKET") != null) return ssc
        val line = "#JACKET:$name;"
        val empty = Regex("""#JACKET\s*:\s*;""", RegexOption.IGNORE_CASE)
        if (empty.containsMatchIn(ssc)) return ssc.replaceFirst(empty, line)
        val music = Regex("""#MUSIC\s*:[^;]*;[ \t]*\r?\n?""", RegexOption.IGNORE_CASE).find(ssc)
            ?: return "$line\n$ssc"
        val at = music.range.last + 1
        val newline = if (music.value.endsWith("\n")) "" else "\n"
        return ssc.substring(0, at) + newline + line + "\n" + ssc.substring(at)
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** A JPEG or a PNG, the two a StepMania theme and every decoder read. */
    private fun isPicture(bytes: ByteArray): Boolean = isPng(bytes) || isJpeg(bytes)

    private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart")
    private val PICTURE_EXTENSIONS = setOf("jpg", "jpeg", "png")
}
