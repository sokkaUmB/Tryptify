// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/**
 * The files under a folder the player picked with the system's folder picker,
 * for [PackImporter.importFolder]: StepMania songs already unpacked, as
 * StepMania keeps them on the phone.
 *
 * Read through [DocumentsContract], one query a folder: a folder of a thousand
 * songs is a thousand queries, where `DocumentFile` would make several per
 * file. Bounded in depth and in count, so a picked folder that is the whole
 * phone stops rather than walking it.
 */
object TreePackSource {

    class Listing(val name: String, val files: List<PackImporter.SourceFile>)

    fun list(context: Context, tree: Uri, isActive: () -> Boolean = { true }): Listing {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = runCatching {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                arrayOf(Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: rootId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Imported pack" }

        val files = ArrayList<PackImporter.SourceFile>()
        fun walk(documentId: String, path: List<String>) {
            if (!isActive()) throw PackImporter.PackImportException("The import was cancelled.")
            if (path.size > MAX_DEPTH) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )
            val folders = ArrayList<Pair<String, String>>()
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val childName = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                        // Hidden folders and macOS's resource forks hold no songs.
                        if (!childName.startsWith(".") && childName != "__MACOSX") folders += id to childName
                    } else {
                        if (files.size >= MAX_FILES) {
                            throw PackImporter.PackImportException("That folder has too many files to be StepMania songs.")
                        }
                        val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        files += PackImporter.SourceFile(path + childName, size) {
                            resolver.openInputStream(uri) ?: throw IOException("$childName could not be read.")
                        }
                    }
                }
            } ?: throw IOException("That folder could not be read.")
            for ((id, folderName) in folders) walk(id, path + folderName)
        }
        walk(rootId, emptyList())
        return Listing(name, files)
    }

    /** StepMania's own folder is three deep to a song (Songs/pack/song); room to spare. */
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 200_000
}
