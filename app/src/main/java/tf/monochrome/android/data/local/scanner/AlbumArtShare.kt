// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.scanner

/**
 * Which cover a song without one of its own borrows from its album, when the
 * library is grouped.
 *
 * Albums are grouped by tags alone, across every folder, so a coverless song
 * tagged "Various Artists", "Unknown Album" or "Singles" lands in one bucket
 * with every unrelated file that carries the same catch-all tag, and used to
 * take whichever of them happened to come first with a cover: a random
 * picture on a song that has none. Two rules now:
 *
 * - A catch-all album name is not an album: nothing shares a cover in it.
 * - A song borrows only from a song of the same album in its own folder,
 *   where an album's tracks live together; the same title elsewhere is
 *   another record, or another copy of it.
 *
 * A song with no cover it can rightly borrow shows none, and "Find album art"
 * is how it gets one.
 */
internal object AlbumArtShare {
    /** Album tags that name no album, only that the file has none. Compared lower-case, trimmed. */
    private val PLACEHOLDERS = setOf(
        "various artists", "various", "va", "v.a.", "v/a",
        "unknown album", "unknown", "untitled", "no album", "none",
        "single", "singles", "music", "misc", "miscellaneous",
    )

    /** Whether [album] is no album at all: blank, or a catch-all tag. */
    fun isPlaceholder(album: String?): Boolean {
        val name = album?.trim()?.lowercase().orEmpty()
        return name.isEmpty() || name in PLACEHOLDERS
    }

    fun folderOf(path: String): String = path.substringBeforeLast('/', "")

    /** A track of one album, as the grouping sees it. */
    data class Member(val path: String, val hasArt: Boolean, val art: String?)

    /**
     * For each of one album's [tracks] by path, the cover a track without its
     * own may show: the first cover in its own folder, or null when that
     * folder has none. Nothing at all for a [placeholder] album.
     */
    fun coversByPath(tracks: List<Member>, placeholder: Boolean): Map<String, String?> {
        if (placeholder) return tracks.associate { it.path to null }
        val byFolder = HashMap<String, String>()
        for (t in tracks) {
            val art = t.art ?: continue
            if (t.hasArt) byFolder.putIfAbsent(folderOf(t.path), art)
        }
        return tracks.associate { it.path to byFolder[folderOf(it.path)] }
    }
}
