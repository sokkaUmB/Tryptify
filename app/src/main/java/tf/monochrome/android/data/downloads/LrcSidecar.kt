// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.downloads

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * An `.lrc` beside a downloaded song, with the song's own stem, which is how
 * players pair the two. The downloader writes one at download time with the
 * line-timed lyrics it found then; this writes (or replaces) it later, with
 * the word-timed lyrics a server or the editor made.
 */
object LrcSidecar {

    /** Writes [lrc] next to the audio at [filePath] (a path, or a SAF document URI). False when it could not. */
    fun write(context: Context, filePath: String, lrc: String): Boolean = runCatching {
        if (filePath.startsWith("content://")) writeBeside(context, filePath, lrc) else writeBesideFile(filePath, lrc)
    }.getOrDefault(false)

    private fun writeBesideFile(path: String, lrc: String): Boolean {
        val audio = File(path)
        val folder = audio.parentFile ?: return false
        if (!folder.isDirectory) return false
        File(folder, audio.nameWithoutExtension + ".lrc").writeText(lrc)
        return true
    }

    /**
     * A document made under a tree carries the tree in its URI, and the
     * external storage provider's ids are paths, so the folder is the id
     * up to its last slash. Created as octet-stream with the extension in
     * the name: as text/plain the provider adds a ".txt" of its own.
     */
    private fun writeBeside(context: Context, uriString: String, lrc: String): Boolean {
        val uri = uriString.toUri()
        val documentId = DocumentsContract.getDocumentId(uri)
        val name = documentId.substringAfterLast('/')
        val folderId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (folderId.isEmpty() || name.isEmpty()) return false
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(uri, folderId)
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return false
        val lrcName = name.substringBeforeLast('.') + ".lrc"
        folder.findFile(lrcName)?.delete()
        val file = folder.createFile("application/octet-stream", lrcName) ?: return false
        context.contentResolver.openOutputStream(file.uri)?.use { it.write(lrc.toByteArray()) } ?: return false
        return true
    }
}
