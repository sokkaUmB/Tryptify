package tf.monochrome.android.domain.model

// The library as the library galaxy reads it: summaries of the device's own
// music, built by `LocalGalaxyRepository` and laid out by `LibraryStarField`
// (artists as stars) and `LocalSystems` (albums as planets, tracks as moons).

/**
 * One artist in the library, as the galaxy needs them: a summary, not their
 * tracks. Built by the repository from a few grouped queries.
 */
data class LibraryArtist(
    /** The artist's normalised name: what the star is keyed by, and what keeps it in place. */
    val key: String,
    val name: String,
    val trackCount: Int,
    /** Their earliest album's year, where any is tagged. */
    val firstYear: Int?,
    /** Each genre tag on their tracks, as written, and how many tracks carry it. */
    val genreTags: Map<String, Int> = emptyMap(),
    val kind: Kind = Kind.ARTIST,
) {
    enum class Kind {
        ARTIST,
        /** Every compilation in the library, as one star near the core. */
        COMPILATIONS,
        /** Tracks with no artist tag at all, as one dim star in the halo. */
        UNKNOWN,
    }
}

/** Two artists on the same tracks — a feature, a collaboration — by key, and on how many. */
data class LibraryCollab(val a: String, val b: String, val tracks: Int)

/** An album in the library, with its tracks, as an artist's system needs it. */
data class LibraryAlbum(
    /** The album's grouping key (`local_albums.groupingKey`): what keeps its look the same between scans. */
    val key: String,
    val title: String,
    val year: Int?,
    val artworkUri: String?,
    val tracks: List<LibraryTrack>,
    /** Plays of its tracks, ever, and lately. */
    val plays: Int = 0,
    val recentPlays: Int = 0,
)

data class LibraryTrack(
    /** Its `local_tracks` row: what a tap plays. */
    val id: Long,
    /** Its file's path: what keeps its moon's place the same between scans. */
    val key: String,
    val title: String,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationSeconds: Int,
    val plays: Int = 0,
)
