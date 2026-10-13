package tf.monochrome.android.data.local.tags

/**
 * What a file's name says about its track, once the numbering is off it.
 *
 * Rips and downloads name files by their place on the release: "01 - Song",
 * "2-05 Song", "05. Song", "CD1 - 05 - Song". A file with no title tag took
 * that whole name as its title, so lyrics were searched for "2-05 Jolene";
 * and one with no artist tag either had it split at the first " - ", so the
 * track number ("01") or the disc ("CD1") became the artist. Every lyrics
 * source then searched for an artist that does not exist.
 */
object TrackNames {

    /**
     * [name] without a leading disc or track number: "01 - Song", "1-05 Song",
     * "05. Song", "CD1 - 05 - Song", "Disc 2 - 03 - Song" all give "Song".
     *
     * Only numbering that is plainly numbering comes off: zero-padded ("05 "),
     * disc-and-track ("2-05 "), or followed by a dot or a spaced dash ("12 - ").
     * A title that starts with a number keeps it — "99 Luftballons", "7 Rings",
     * "1999" — and a name that is nothing but a number stays as it is.
     */
    fun withoutNumberPrefix(name: String): String {
        val stripped = NUMBER_PREFIX.replaceFirst(name, "").trim()
        return stripped.ifEmpty { name.trim() }
    }

    /**
     * Whether [artist] is numbering an older scan took for an artist: "01",
     * "1-05", "CD1", "Disc 2". Such a row is re-read so the fix reaches it.
     */
    fun looksLikeNumbering(artist: String?): Boolean =
        artist != null && NUMBERING_ONLY.matches(artist.trim())

    // A separator after a number: a dash, underscore or dot, or plain space.
    private const val SEP = """(?:\s*[-–_.]\s*|\s+)"""

    // The track number itself, in the forms that are plainly numbering.
    private const val TRACK =
        """(?:\d{1,2}[-.]\d{1,3}$SEP|0\d{1,2}$SEP|\d{1,3}(?:\s*[-–_]\s+|\.\s*))"""

    private val NUMBER_PREFIX = Regex(
        """^\s*(?:(?:cd|disc|disk)\s*\d{1,2}$SEP(?:$TRACK)?|$TRACK)""",
        RegexOption.IGNORE_CASE,
    )

    private val NUMBERING_ONLY = Regex(
        """(?:(?:cd|disc|disk)\s*\d{1,2}|\d{1,3}(?:[-.]\d{1,3})?)""",
        RegexOption.IGNORE_CASE,
    )
}
