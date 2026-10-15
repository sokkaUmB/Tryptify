// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

/**
 * StepMania's music wheel, as data: the songs in folders, and the items the
 * wheel turns through.
 *
 * As in StepMania, the folders are what the sort makes them
 * (`metrics.ini`, `[MusicWheel]`):
 *
 * * Pack — StepMania's group sort: a folder per imported pack, and the
 *   player's own library as one more.
 * * BPM — bands of ten (`SortBPMDivision=10`), unknown tempos last.
 * * Duration — one folder a minute (`SortLengthDivision=60`).
 * * Name — a folder a letter, `#` for anything else.
 * * Mapped — ready to play, then not mapped yet.
 *
 * The wheel shows every folder, and the songs of the one folder that is open
 * straight after it. One open at a time, as StepMania's sections: opening
 * another closes the first. A search is one open folder of its results.
 */
object GlyphWheel {

    const val LIBRARY = "Your library"

    /** One folder: its name and its songs, already in the sort's order. */
    data class Section(val name: String, val songs: List<GlyphSong>) {
        val charted: Int get() = songs.count { it.hasChart }
    }

    sealed interface Item {
        /** Stable across rebuilds, so the wheel keeps its place when a folder opens. */
        val key: String

        // Stored, not computed: the wheel looks items up by key on every change.
        data class Folder(val section: Section, val open: Boolean) : Item {
            override val key: String = folderKey(section.name)
        }

        data class Song(val song: GlyphSong, val section: String) : Item {
            override val key: String = songKey(song.trackId)
        }
    }

    fun folderKey(name: String) = "folder:$name"
    fun songKey(trackId: String) = "$SONG$trackId"

    /** The track a song item's [key] names, or null for a folder's or none. */
    fun trackIdOf(key: String?): String? = key?.takeIf { it.startsWith(SONG) }?.removePrefix(SONG)

    private const val SONG = "song:"

    /** The folders [songs] fall into under [sort], each in the sort's order; a search makes one. */
    fun sections(songs: List<GlyphSong>, sort: GlyphSongSort, query: String = ""): List<Section> {
        if (query.isNotBlank()) {
            // Trimmed: a stray space from the keyboard should not hide every song.
            val q = query.trim()
            val found = sort.apply(
                songs.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) },
            )
            return if (found.isEmpty()) emptyList() else listOf(Section(searchName(query), found))
        }
        val sorted = sort.apply(songs)
        // Sorted first, then grouped in order: folders come out in the sort's
        // order (descending turns them round too), songs in it within each.
        val grouped = LinkedHashMap<String, MutableList<GlyphSong>>()
        for (song in sorted) grouped.getOrPut(sectionOf(song, sort.key)) { ArrayList() } += song
        return grouped.map { (name, list) -> Section(name, list) }
    }

    /** The name of a search's folder. */
    fun searchName(query: String) = "Results for \"${query.trim()}\""

    /** Which folder [song] falls in under [key]. */
    fun sectionOf(song: GlyphSong, key: GlyphSortKey): String = when (key) {
        // A pack that happens to be called what the library is stays its own folder.
        GlyphSortKey.PACK -> song.pack?.let { if (it == LIBRARY) "$it (pack)" else it } ?: LIBRARY
        GlyphSortKey.MAPPED -> if (song.hasChart) "Ready to play" else "Not mapped yet"
        GlyphSortKey.NAME -> song.title.trim().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
        GlyphSortKey.DURATION -> {
            val minute = song.durationSeconds.coerceAtLeast(0) / 60
            "$minute:00 – ${minute + 1}:00"
        }
        GlyphSortKey.BPM -> song.bpm?.let { bpm ->
            val band = (bpm.toInt() / 10) * 10
            "$band – ${band + 9} BPM"
        } ?: "BPM unknown"
    }

    /** What the wheel turns through: every folder, and the open one's songs after it. */
    fun items(sections: List<Section>, open: String?): List<Item> {
        val items = ArrayList<Item>(sections.size + (sections.firstOrNull { it.name == open }?.songs?.size ?: 0))
        for (section in sections) {
            val isOpen = section.name == open
            items += Item.Folder(section, isOpen)
            if (isOpen) section.songs.forEach { items += Item.Song(it, section.name) }
        }
        return items
    }

    /** Where [key] is in [items], or -1. */
    fun indexOf(items: List<Item>, key: String?): Int = if (key == null) -1 else items.indexOfFirst { it.key == key }

    /**
     * The item to centre when the wheel is built: the focused one when it is
     * still there, else the folder a focused song now sits in (closed by a
     * sort or a search), else the first.
     */
    fun anchor(items: List<Item>, focused: String?, focusedSection: String?): Int {
        indexOf(items, focused).takeIf { it >= 0 }?.let { return it }
        focusedSection?.let { name -> indexOf(items, folderKey(name)).takeIf { it >= 0 }?.let { return it } }
        return 0
    }
}
