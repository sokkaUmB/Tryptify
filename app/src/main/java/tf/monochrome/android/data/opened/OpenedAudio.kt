package tf.monochrome.android.data.opened

import java.security.MessageDigest
import java.util.Locale

/**
 * The rules for an audio file another app hands Tryptify ("Open with"), kept
 * free of Android so every refusal is tested: which requests are taken at
 * all, what the temporary copy is called, and which old copies go.
 *
 * Every VIEW intent the activity does not recognise used to be handed to the
 * sign-in callback, so a song opened from a file manager reached Supabase as a
 * failed login. An opened file is checked here first, and a request that fails
 * the check is dropped, never passed on.
 */
object OpenedAudio {

    /** The id prefix of a track played from an opened file; never `local_`, which names a library row. */
    const val ID_PREFIX = "opened_"

    /** The largest file copied: past this it is refused rather than filling the cache. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** Room left on the disk after the copy, so opening a song never fills the phone. */
    const val SPARE_BYTES = 64L * 1024 * 1024

    /** Copies kept in the cache: the one playing and a couple before it. */
    const val KEEP_COPIES = 3

    /** Where "Add to library" puts a song, under the shared Music folder. */
    const val LIBRARY_FOLDER = "Tryptify"

    /** Types some apps give audio that are not `audio/…`. */
    private val AUDIO_APPLICATION_TYPES = setOf(
        "application/ogg", "application/x-ogg", "application/flac", "application/x-flac",
    )

    enum class Refusal {
        /** Not ACTION_VIEW, no data, or a scheme other than content and file. */
        NOT_A_FILE,
        /** The type says it is not audio. */
        NOT_AUDIO,
        /** A path inside Tryptify's own storage, or one of its own providers. */
        PRIVATE,
    }

    fun isAudioType(mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return (type.startsWith("audio/") && type.length > "audio/".length) || type in AUDIO_APPLICATION_TYPES
    }

    /**
     * Why the request is refused, or null when it is taken.
     *
     * [canonicalPath] is the file:// path resolved through any `..` and links,
     * so a path that only looks public cannot reach the app's own files.
     * [privateRoots] are those files (data, cache, external files), and a
     * content authority named after [ownPackage] is one of the app's own
     * providers: another app must not get Tryptify to read out its private
     * files by "opening" them.
     */
    fun refusal(
        action: String?,
        scheme: String?,
        mime: String?,
        authority: String?,
        canonicalPath: String?,
        privateRoots: List<String>,
        ownPackage: String,
    ): Refusal? {
        if (action != ACTION_VIEW) return Refusal.NOT_A_FILE
        when (scheme?.lowercase(Locale.ROOT)) {
            "content" -> if (authority.isNullOrBlank()) return Refusal.NOT_A_FILE
            "file" -> if (canonicalPath.isNullOrBlank()) return Refusal.NOT_A_FILE
            else -> return Refusal.NOT_A_FILE
        }
        if (!isAudioType(mime)) return Refusal.NOT_AUDIO
        if (authority != null && (authority == ownPackage || authority.startsWith("$ownPackage."))) return Refusal.PRIVATE
        if (canonicalPath != null && (APP_DATA_ROOTS + privateRoots).any { isUnder(canonicalPath, it) }) {
            return Refusal.PRIVATE
        }
        return null
    }

    /**
     * A file name safe to write: no folders, no control characters, not
     * hidden, at most [MAX_NAME] characters with its extension kept. Falls
     * back to "audio" plus [fallbackExtension] when nothing usable is left.
     */
    fun safeName(displayName: String?, fallbackExtension: String?): String {
        val base = displayName.orEmpty()
            .substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trim().trimStart('.')
        val ext = fallbackExtension?.trim('.')?.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
        val named = base.ifEmpty { if (ext != null) "audio.$ext" else "audio" }
        val withExt = if (ext != null && !named.contains('.')) "$named.$ext" else named
        if (withExt.length <= MAX_NAME) return withExt
        val dot = withExt.lastIndexOf('.')
        val tail = if (dot > 0 && withExt.length - dot <= 8) withExt.substring(dot) else ""
        return withExt.take(MAX_NAME - tail.length) + tail
    }

    /** The title shown when the file has no title tag: its name, without the extension. */
    fun titleFromName(name: String): String =
        name.substringBeforeLast('.').ifBlank { name }.replace('_', ' ').trim()

    /** The folder one opened file is copied into: the same file opened twice shares it. */
    fun copyKey(uri: String, size: Long): String = sha1("$uri|$size").take(16)

    /** The track id for a copy, so the same file keeps its id and its likes. */
    fun trackId(copyKey: String): String = ID_PREFIX + copyKey

    /** The copies to delete from [copies] (key to last used), keeping the newest [keep] and [keepKey]. */
    fun copiesToDrop(copies: Map<String, Long>, keepKey: String?, keep: Int = KEEP_COPIES): Set<String> =
        copies.entries
            .filter { it.key != keepKey }
            .sortedByDescending { it.value }
            .drop((if (keepKey != null) keep - 1 else keep).coerceAtLeast(0))
            .mapTo(HashSet()) { it.key }

    /** Whether a copy of [size] bytes fits, leaving [SPARE_BYTES]; an unknown size (-1) is checked as it streams. */
    fun fits(size: Long, usable: Long): Boolean = size <= MAX_BYTES && (size < 0 || size + SPARE_BYTES <= usable)

    private fun isUnder(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return r.isNotEmpty() && (path == r || path.startsWith("$r/"))
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Every app's private storage, whatever the per-user path: never read through "Open with". */
    private val APP_DATA_ROOTS = listOf("/data/data", "/data/user", "/data/user_de")

    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val MAX_NAME = 120
}
