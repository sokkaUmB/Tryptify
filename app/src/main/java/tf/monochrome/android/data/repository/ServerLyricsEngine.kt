// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.stepmania.ChartMode
import tf.monochrome.android.audio.stepmania.RemoteStemAnalysis
import tf.monochrome.android.data.api.LyricsQuery
import tf.monochrome.android.data.api.ServerLyricsClient
import tf.monochrome.android.data.cache.DeezerStreamCacheManager
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.LyricsCacheDao
import tf.monochrome.android.data.db.entity.CachedLyricsEntity
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/** Where a song's server timing stands, for the lyrics view; nothing while there is nothing to say. */
sealed interface ServerLyricsStatus {
    /** The song is on its way to, or being heard by, [host]. */
    data class Working(val host: String) : ServerLyricsStatus

    /** Every server failed; [reason] is the last one's. */
    data class Failed(val reason: String) : ServerLyricsStatus

    /** The song has no file on the phone to send: a stream that was never downloaded. */
    data object NoFile : ServerLyricsStatus

    /** No server under Settings › Connections times lyrics. */
    data object NoServer : ServerLyricsStatus
}

/**
 * Times a song's lyrics on a server, in the background, and keeps the answer.
 *
 * The lyrics race ([LyricsResolver]) has fifteen seconds; a server has the
 * song to separate and hear, which is minutes on a CPU. So the race answers
 * first with what the catalogues have, and this runs after it, on its own
 * clock: a song that gets no word-timed lyrics from the race is sent here
 * ([request]), with whatever plain lyrics the race did find so the server
 * keeps the words and adds the times. The answer lands in the cache
 * ([LyricsCacheDao]), which [MusicRepository.getLyrics] reads before any
 * race, and in [results], which the player shows if the song is still on.
 *
 * A job outlives the screen and the track: skipping a song halfway through
 * its upload would otherwise throw the server's minutes away, and the
 * answer is as good next time.
 */
@Singleton
class ServerLyricsEngine @Inject constructor(
    private val client: ServerLyricsClient,
    private val cache: LyricsCacheDao,
    private val downloadDao: DownloadDao,
    private val qobuzCache: QobuzStreamCacheManager,
    private val deezerCache: DeezerStreamCacheManager,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Long, Job>()

    private val _status = MutableStateFlow<Map<Long, ServerLyricsStatus>>(emptyMap())

    /** Each song being timed, or that could not be, by track id. */
    val status: StateFlow<Map<Long, ServerLyricsStatus>> = _status.asStateFlow()

    private val _results = MutableSharedFlow<Pair<Long, Lyrics>>(extraBufferCapacity = 8)

    /** Each song's lyrics as a server finishes them, by track id. */
    val results: SharedFlow<Pair<Long, Lyrics>> = _results.asSharedFlow()

    /** Whether any server under Settings › Connections times lyrics. */
    val hasServer: Flow<Boolean> = client.serverFlow.map { it.isNotEmpty() }

    /** The lyrics a server timed for [trackId] before, if any. */
    suspend fun cached(trackId: Long, convertToRomaji: Boolean): Lyrics? =
        cache.get(trackId)?.let { ServerLyricsClient.fromCached(json, it.lyricsJson, convertToRomaji)?.lyrics }

    /**
     * Sends [track] to the servers, one after another until one answers,
     * unless it is already on its way. [plain] is the lyrics as the app has
     * them, line-timed or not, for the server to keep; null, and the
     * server's own hearing is the lyrics. False when a job was already running.
     */
    fun request(
        track: Track,
        unified: UnifiedTrack?,
        query: LyricsQuery?,
        plain: String?,
        convertToRomaji: Boolean,
    ): Boolean {
        synchronized(jobs) {
            if (jobs[track.id]?.isActive == true) return false
            jobs[track.id] = scope.launch { run(track, unified, query, plain, convertToRomaji) }
        }
        return true
    }

    private suspend fun run(track: Track, unified: UnifiedTrack?, query: LyricsQuery?, plain: String?, convertToRomaji: Boolean) {
        // A GPU first, then any that has not said, then the CPU ones: the
        // same order a Fast chart takes, for the same reason.
        val servers = RemoteStemAnalysis.planFor(client.servers(), ChartMode.FAST)
        if (servers.isEmpty()) {
            set(track.id, ServerLyricsStatus.NoServer)
            return
        }
        val source = sourceFor(track, unified)
        if (source == null) {
            set(track.id, ServerLyricsStatus.NoFile)
            return
        }
        var reason = "no server answered"
        for (server in servers) {
            val host = ServerLyricsClient.hostOf(server.url)
            set(track.id, ServerLyricsStatus.Working(host))
            val result = try {
                client.sync(server.url, source, query, plain, convertToRomaji)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                reason = e.message ?: e::class.simpleName.orEmpty()
                Log.w(TAG, "\"${track.title}\": $host failed: $reason")
                continue
            }
            cache.put(CachedLyricsEntity(trackId = track.id, lyricsJson = result.dataJson, isSynced = true))
            _status.update { it - track.id }
            _results.emit(track.id to result.lyrics)
            Log.i(TAG, "\"${track.title}\": ${result.mode} by ${result.label}, ${result.lyrics.lines.size} lines, coverage ${result.coverage}")
            return
        }
        set(track.id, ServerLyricsStatus.Failed(reason))
    }

    private fun set(trackId: Long, status: ServerLyricsStatus) = _status.update { it + (trackId to status) }

    /**
     * The song's file on the phone, if it has one: a scanned file, a
     * download, or a stream the cache kept whole after a play. A stream
     * never downloaded (TIDAL, Apple) has none, and is not fetched for this.
     */
    suspend fun sourceFor(track: Track, unified: UnifiedTrack?): Uri? {
        (unified?.source as? PlaybackSource.LocalFile)?.let { uriOf(it.filePath)?.let { uri -> return uri } }
        runCatching { downloadDao.getDownloadedTrack(track.id) }.getOrNull()?.let { uriOf(it.filePath)?.let { uri -> return uri } }
        for (quality in arrayOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES, AudioQuality.HIGH, AudioQuality.LOW)) {
            runCatching { qobuzCache.peekCached(track.id, quality) }.getOrNull()?.let { return Uri.fromFile(it) }
            runCatching { deezerCache.peekCached(track.deezerId ?: track.id, quality) }.getOrNull()?.let { return Uri.fromFile(it) }
        }
        return null
    }

    private fun uriOf(pathOrUri: String): Uri? = when {
        pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://") -> pathOrUri.toUri()
        else -> File(pathOrUri).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    }

    private companion object {
        const val TAG = "ServerLyrics"
    }
}
