// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.local.art

import android.media.MediaMetadataRetriever
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.tags.ArtworkStore
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.buildCoverUrl

/** A song on the phone to find a cover for. */
data class ArtRequest(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int,
    val filePath: String,
)

/** How a search went: covers found, songs that had their own, songs with none found. */
data class ArtOutcome(val found: Int, val ownCover: Int, val notFound: Int) {
    val total: Int get() = found + ownCover + notFound
}

/**
 * "Find album art": looks a cover up online for songs on the phone that have
 * none, one from a song's ⋮ menu or many from a selection. The catalogue is
 * asked first, by artist and title, then the iTunes search; a result counts
 * only when it is the same song ([ArtMatch]), so a song with no sure match is
 * left without a cover rather than given a wrong one.
 *
 * A cover found is kept in the app's own artwork store ([ArtworkStore.putFound])
 * and shown at once; the song's file is never written to, and the scanner
 * reads the found cover back on every rescan. A song with its own embedded
 * picture is left as it is.
 */
@Singleton
class AlbumArtFinder @Inject constructor(
    private val http: HttpClient,
    private val json: Json,
    private val music: MusicRepository,
    private val store: ArtworkStore,
    private val dao: LocalMediaDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _busy = MutableStateFlow(0)

    /** Songs waiting or being looked up now. */
    val busy: StateFlow<Int> = _busy.asStateFlow()
    private val _outcomes = MutableSharedFlow<ArtOutcome>(extraBufferCapacity = 8)

    /** One per [find], when all its songs are done. */
    val outcomes: SharedFlow<ArtOutcome> = _outcomes.asSharedFlow()

    /** Looks covers up for [songs], one at a time, in the background; [outcomes] says how it went. */
    fun find(songs: List<ArtRequest>) {
        if (songs.isEmpty()) return
        _busy.value += songs.size
        scope.launch {
            lock.withLock {
                var found = 0
                var own = 0
                var none = 0
                for (song in songs) {
                    when (runCatching { findOne(song) }.onFailure { Log.w(TAG, "cover for ${song.title}: ${it.message}") }.getOrNull()) {
                        One.FOUND -> found++
                        One.OWN -> own++
                        else -> none++
                    }
                    _busy.value = (_busy.value - 1).coerceAtLeast(0)
                }
                _outcomes.tryEmit(ArtOutcome(found, own, none))
            }
        }
    }

    private enum class One { FOUND, OWN, NONE }

    private suspend fun findOne(song: ArtRequest): One {
        if (hasOwnPicture(song.filePath)) return One.OWN
        val want = ArtMatch.Want(song.title, song.artist, song.durationSeconds * 1000L)
        val url = fromCatalogue(want) ?: fromITunes(want) ?: return One.NONE
        val response = http.get(url)
        if (!response.status.isSuccess()) return One.NONE
        val bytes: ByteArray = response.body()
        val key = store.putFound(bytes, song.filePath) ?: return One.NONE
        dao.setFoundArtwork(song.filePath, key)
        return One.FOUND
    }

    private fun hasOwnPicture(path: String): Boolean = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.embeddedPicture != null
        } finally {
            retriever.release()
        }
    }.getOrDefault(false)

    private suspend fun fromCatalogue(want: ArtMatch.Want): String? {
        val query = ArtMatch.query(want)
        val found = music.searchTracks(query, limit = 15).getOrNull().orEmpty()
        val candidates = found.mapNotNull { t ->
            val cover = t.album?.cover ?: return@mapNotNull null
            ArtMatch.Candidate(t.title, t.displayArtist, t.duration * 1000L, buildCoverUrl(cover, 1280))
        }
        return ArtMatch.best(want, candidates)?.coverUrl?.takeIf { it.startsWith("http") }
    }

    private suspend fun fromITunes(want: ArtMatch.Want): String? {
        val term = URLEncoder.encode(ArtMatch.query(want), "UTF-8")
        val response = http.get("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=$term")
        if (!response.status.isSuccess()) return null
        val results = json.parseToJsonElement(response.bodyAsText()).jsonObject["results"]?.jsonArray.orEmpty()
        val candidates = results.mapNotNull { r ->
            val o = r.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val art = str("artworkUrl100") ?: return@mapNotNull null
            ArtMatch.Candidate(
                title = str("trackName").orEmpty(),
                artist = str("artistName").orEmpty(),
                durationMs = o["trackTimeMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                coverUrl = art.replace("100x100bb", "1200x1200bb"),
            )
        }
        return ArtMatch.best(want, candidates)?.coverUrl
    }

    private companion object {
        const val TAG = "AlbumArtFinder"
    }
}

/** Whether a search result is the song looked for. */
object ArtMatch {
    data class Want(val title: String, val artist: String, val durationMs: Long)
    data class Candidate(val title: String, val artist: String, val durationMs: Long, val coverUrl: String)

    /** Artist tags that name nobody, so they cannot vouch for a match. */
    private val NO_ARTIST = setOf("", "unknown artist", "unknown", "various artists", "various", "va")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The title without "(Radio Edit)", "[Remastered]", "- 2011 Remaster" and the like. */
    private fun core(title: String) = norm(title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").substringBefore(" - "))

    /** What to search for: the artist and the title's core, the artist left out when it names nobody. */
    fun query(want: Want): String {
        val title = want.title.replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim()
        val artist = want.artist.takeUnless { it.trim().lowercase() in NO_ARTIST }.orEmpty()
        return listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The candidate that is [want]: the same title, and either the same
     * artist within 5 s of the length, or (when the song's artist names
     * nobody) within 3 s. The closest length wins; none is better than a guess.
     */
    fun best(want: Want, candidates: List<Candidate>): Candidate? {
        val title = core(want.title)
        if (title.isEmpty()) return null
        val anonymous = want.artist.trim().lowercase() in NO_ARTIST
        val artist = norm(want.artist)
        fun lengthOff(c: Candidate) =
            if (want.durationMs <= 0 || c.durationMs <= 0) null else kotlin.math.abs(c.durationMs - want.durationMs)
        return candidates
            .filter { core(it.title) == title }
            .filter { c ->
                val off = lengthOff(c)
                if (anonymous) {
                    off != null && off <= 3_000
                } else {
                    val theirs = norm(c.artist)
                    val sameArtist = theirs.isNotEmpty() && (theirs.contains(artist) || artist.contains(theirs))
                    sameArtist && (off == null || off <= 5_000)
                }
            }
            .minByOrNull { lengthOff(it) ?: Long.MAX_VALUE }
    }
}
