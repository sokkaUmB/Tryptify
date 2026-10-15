// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream

/**
 * A pack written into a folder the player chose with Android's folder picker.
 *
 * Through the document provider, since the folder may be anywhere — the phone's
 * storage, an SD card, a cloud drive's folder. The folder is a pack's parent,
 * as StepMania's `Songs` folder is: pointing StepMania at it finds the pack.
 */
class TreePackSink(context: Context, private val tree: Uri) : PackSink {

    private val resolver = context.contentResolver
    private val rootDocument: Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun exists(path: List<String>): Boolean = locate(path, create = false) != null

    override fun write(path: List<String>, content: (OutputStream) -> Unit) {
        val folder = locate(path.dropLast(1), create = true) ?: throw IOException("Could not make the folder ${path.dropLast(1).joinToString("/")}.")
        val name = path.last()
        val file = child(folder, name)
            ?: DocumentsContract.createDocument(resolver, folder, mimeOf(name), name)
            ?: throw IOException("Could not create $name.")
        // "wt": truncate, so a chart rewritten shorter leaves no old tail.
        (resolver.openOutputStream(file, "wt") ?: throw IOException("Could not write $name.")).buffered().use(content)
    }

    /** The document at [path] under the tree; folders made on the way when [create]. */
    private fun locate(path: List<String>, create: Boolean): Uri? {
        var current = rootDocument
        for ((index, name) in path.withIndex()) {
            val found = child(current, name)
            current = when {
                found != null -> found
                create && index < path.size -> DocumentsContract.createDocument(
                    resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, name,
                ) ?: return null
                else -> return null
            }
        }
        return current
    }

    /** [parent]'s child called [name], matched as the folder's own system would. */
    private fun child(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).equals(name, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        return null
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(StepManiaPack.extension(name)) ?: "application/octet-stream"

    companion object {
        /** Keep reading and writing the picked folder across restarts. */
        fun keep(context: Context, tree: Uri) {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        /** Whether the app still holds the grant for [tree] (a folder can be revoked, or deleted). */
        fun isUsable(context: Context, tree: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

        /** A readable name for [tree]: "Music/StepMania" rather than a document id. */
        fun displayName(tree: Uri): String =
            runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Phone storage" } }
                .getOrDefault("the chosen folder")
    }
}
