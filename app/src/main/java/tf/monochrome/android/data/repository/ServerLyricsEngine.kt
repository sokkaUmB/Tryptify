// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.stepmania.ChartMode
import tf.monochrome.android.audio.stepmania.RemoteStemAnalysis
import tf.monochrome.android.data.api.LrcFormat
import tf.monochrome.android.data.api.LyricsQuery
import tf.monochrome.android.data.api.ServerLyricsClient
import tf.monochrome.android.data.cache.DeezerStreamCacheManager
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.LyricsCacheDao
import tf.monochrome.android.data.db.entity.CachedLyricsEntity
import tf.monochrome.android.data.downloads.DownloadManager
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.data.downloads.LrcSidecar
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/** One song in line for its times, with what the app knows of it. */
data class LyricsJob(
    val track: Track,
    val unified: UnifiedTrack?,
    val query: LyricsQuery?,
    /** The lyrics as the app has them, timed or not, for the server to keep; null and it hears its own. */
    val plain: String?,
    val convertToRomaji: Boolean,
)

/** What a job is doing, for the notification and the lyrics view. */
enum class LyricsStage { DOWNLOADING, SENDING, HEARING }

/** Where a song's timing stands, for the lyrics view; nothing while there is nothing to say. */
sealed interface ServerLyricsStatus {
    /** In line behind another song. */
    data object Waiting : ServerLyricsStatus

    /** On its way to, or being heard by, [host]. */
    data class Working(val host: String, val stage: LyricsStage) : ServerLyricsStatus

    /** Every server failed; [reason] is the last one's. */
    data class Failed(val reason: String) : ServerLyricsStatus

    /** The song has no file on the phone to send, and could not be downloaded. */
    data object NoFile : ServerLyricsStatus

    /** No server under Settings › Connections times lyrics. */
    data object NoServer : ServerLyricsStatus
}

/** How one job ended, for the notification left behind. */
data class LyricsOutcome(val title: String, val failure: String?)

/**
 * Times a song's lyrics on a server, in the background, and keeps the answer.
 *
 * The lyrics race ([LyricsResolver]) has fifteen seconds; a server has the
 * song to separate and hear, which is minutes on a CPU. So the race answers
 * first with what the catalogues have, and this runs after it, on its own
 * clock, as Trypt Mania's charts do: songs go in a line ([request]), a
 * worker ([LyricsSyncWorker]) drains it under a notification, and each
 * answer lands in the cache ([LyricsCacheDao]), which
 * [MusicRepository.getLyrics] reads before any race, in [results], which the
 * player shows if the song is still on, and beside the song's download as an
 * `.lrc` when downloads carry lyrics.
 *
 * A song with no file on the phone is downloaded first, as Trypt Mania
 * charts only what is on the phone: the server needs the file, and the
 * download is kept.
 */
@Singleton
class ServerLyricsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: ServerLyricsClient,
    private val cache: LyricsCacheDao,
    private val downloadDao: DownloadDao,
    private val downloads: DownloadManager,
    private val qobuzCache: QobuzStreamCacheManager,
    private val deezerCache: DeezerStreamCacheManager,
    private val preferences: PreferencesManager,
    private val json: Json,
) {
    private val waiting = ArrayDeque<LyricsJob>()
    private var running: Long? = null

    private val _status = MutableStateFlow<Map<Long, ServerLyricsStatus>>(emptyMap())

    /** Each song in line, being timed, or that could not be, by track id. */
    val status: StateFlow<Map<Long, ServerLyricsStatus>> = _status.asStateFlow()

    private val _results = MutableSharedFlow<Pair<Long, Lyrics>>(extraBufferCapacity = 8)

    /** Each song's lyrics as a server (or the editor) finishes them, by track id. */
    val results: SharedFlow<Pair<Long, Lyrics>> = _results.asSharedFlow()

    /** Whether any server under Settings › Connections times lyrics. */
    val hasServer: Flow<Boolean> = client.serverFlow.map { it.isNotEmpty() }

    /** The lyrics a server or the editor kept for [trackId] before, if any. */
    suspend fun cached(trackId: Long, convertToRomaji: Boolean): Lyrics? =
        cache.get(trackId)?.let { ServerLyricsClient.fromCached(json, it.lyricsJson, convertToRomaji)?.lyrics }

    /**
     * Puts [job] in line and starts the worker, unless its song is already
     * in line or being timed. False then.
     */
    fun request(job: LyricsJob): Boolean {
        synchronized(waiting) {
            if (running == job.track.id || waiting.any { it.track.id == job.track.id }) return false
            waiting.addLast(job)
        }
        set(job.track.id, ServerLyricsStatus.Waiting)
        LyricsSyncWorker.start(context)
        return true
    }

    /** Whether anything is in line or being timed. */
    fun hasWork(): Boolean = synchronized(waiting) { waiting.isNotEmpty() || running != null }

    /** The next song for the worker, now the one being timed; null when the line is empty. */
    fun next(): LyricsJob? = synchronized(waiting) {
        waiting.removeFirstOrNull()?.also { running = it.track.id }
    }

    /** Empties the line and stops the worker; the song being timed is dropped with it. */
    fun cancelAll() {
        val dropped = synchronized(waiting) {
            val ids = waiting.map { it.track.id } + listOfNotNull(running)
            waiting.clear()
            ids
        }
        _status.update { it - dropped.toSet() }
        LyricsSyncWorker.cancel(context)
    }

    /** Lyrics made by hand (the editor) for [trackId]: kept as a server's would be, and shown. */
    suspend fun publish(trackId: Long, lyrics: Lyrics, lrc: String?) {
        cache.put(CachedLyricsEntity(trackId = trackId, lyricsJson = ServerLyricsClient.toData(lyrics, lrc), isSynced = true))
        _results.emit(trackId to lyrics)
        if (lrc != null) sidecar(trackId, lrc)
    }

    /** Forgets what was kept for [trackId], so the next lookup races the catalogues again. */
    suspend fun forget(trackId: Long) = cache.delete(trackId)

    /**
     * Times [job] on the servers, one after another until one answers, and
     * reports each stage to [onStage]. Called by the worker; what it returns
     * is for the notification the worker leaves.
     */
    suspend fun run(job: LyricsJob, onStage: (LyricsStage) -> Unit): LyricsOutcome {
        val track = job.track
        try {
            val servers = RemoteStemAnalysis.planFor(client.servers(), ChartMode.FAST)
            if (servers.isEmpty()) {
                set(track.id, ServerLyricsStatus.NoServer)
                return LyricsOutcome(track.title, "no server times lyrics")
            }
            val first = ServerLyricsClient.hostOf(servers.first().url)
            var source = sourceFor(track, job.unified)
            if (source == null) {
                set(track.id, ServerLyricsStatus.Working(first, LyricsStage.DOWNLOADING))
                onStage(LyricsStage.DOWNLOADING)
                source = download(track, job.unified)
            }
            if (source == null) {
                set(track.id, ServerLyricsStatus.NoFile)
                return LyricsOutcome(track.title, "the song could not be downloaded")
            }
            var reason = "no server answered"
            for (server in servers) {
                val host = ServerLyricsClient.hostOf(server.url)
                set(track.id, ServerLyricsStatus.Working(host, LyricsStage.SENDING))
                onStage(LyricsStage.SENDING)
                val result = try {
                    client.sync(server.url, source, job.query, job.plain, job.convertToRomaji) {
                        set(track.id, ServerLyricsStatus.Working(host, LyricsStage.HEARING))
                        onStage(LyricsStage.HEARING)
                    }
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
                result.lrc?.let { sidecar(track.id, it) }
                Log.i(TAG, "\"${track.title}\": ${result.mode} by ${result.label}, ${result.lyrics.lines.size} lines, coverage ${result.coverage}")
                return LyricsOutcome(track.title, null)
            }
            set(track.id, ServerLyricsStatus.Failed(reason))
            return LyricsOutcome(track.title, reason)
        } catch (c: CancellationException) {
            _status.update { it - track.id }
            throw c
        } finally {
            synchronized(waiting) { if (running == track.id) running = null }
        }
    }

    private fun set(trackId: Long, status: ServerLyricsStatus) = _status.update { it + (trackId to status) }

    /**
     * The song's file on the phone, if it has one: a scanned file, a
     * download, or a stream the cache kept whole after a play.
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

    /**
     * Downloads [track] through the app's own downloads (so it stays
     * downloaded) and waits for the file; null when it fails or is not a
     * catalogue track. A live station or a scanned file is never fetched.
     */
    private suspend fun download(track: Track, unified: UnifiedTrack?): Uri? {
        val type = unified?.sourceType
        if (type == SourceType.LOCAL || type == SourceType.LIVE_RADIO || unified?.source is PlaybackSource.RadioStream) return null
        downloads.downloadTrack(track)
        val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            merge(
                downloadDao.isDownloadedFlow(track.id).filter { it }.map { true },
                downloads.observeDownloadState(track.id).filter { it.status == DownloadStatus.FAILED }.map { false },
            ).first()
        } ?: false
        if (!done) return null
        return runCatching { downloadDao.getDownloadedTrack(track.id) }.getOrNull()?.let { uriOf(it.filePath) }
    }

    /** The `.lrc` beside the song's download, when downloads carry lyrics and the song has one. */
    private suspend fun sidecar(trackId: Long, lrc: String) {
        if (!preferences.downloadLyrics.first()) return
        val downloaded = runCatching { downloadDao.getDownloadedTrack(trackId) }.getOrNull() ?: return
        if (!LrcSidecar.write(context, downloaded.filePath, lrc)) Log.w(TAG, "could not write the .lrc beside ${downloaded.filePath}")
    }

    private fun uriOf(pathOrUri: String): Uri? = when {
        pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://") -> pathOrUri.toUri()
        else -> File(pathOrUri).takeIf { it.exists() }?.let { Uri.fromFile(it) }
    }

    /** The LRC the cache holds for [trackId], or one written from its lines. */
    suspend fun lrcOf(trackId: Long, title: String?, artist: String?, lengthMs: Long?): String? {
        val kept = cache.get(trackId) ?: return null
        val back = ServerLyricsClient.fromCached(json, kept.lyricsJson, convertToRomaji = false) ?: return null
        return back.lrc ?: LrcFormat.write(back.lyrics, title, artist, lengthMs)
    }

    private companion object {
        const val TAG = "ServerLyrics"
        const val DOWNLOAD_TIMEOUT_MS = 20 * 60 * 1000L
    }
}
