package tf.monochrome.android.data.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import tf.monochrome.android.R
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.locale.AppLanguage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The song being sent now: [sent] of [total] bytes. */
data class CloudUploadProgress(val title: String, val sent: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}

/** How the last upload ended: [error] is null when it worked. */
data class CloudUploadResult(val title: String, val error: CloudError?)

/**
 * The upload queue. Each song is its own work request, appended to one unique
 * chain so they go one at a time; the chain survives the app being closed.
 */
@Singleton
class CloudUploads @Inject constructor(@ApplicationContext private val context: Context) {
    private val _progress = MutableStateFlow<CloudUploadProgress?>(null)
    val progress: StateFlow<CloudUploadProgress?> = _progress.asStateFlow()

    private val _last = MutableStateFlow<CloudUploadResult?>(null)
    val last: StateFlow<CloudUploadResult?> = _last.asStateFlow()

    /** Songs waiting or being sent. */
    val pending: Flow<Int> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map { infos -> infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } }

    /** Queues the local file at [filePath] for upload. */
    fun enqueue(filePath: String) {
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setInputData(workDataOf(KEY_PATH to filePath))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        _progress.value = null
    }

    internal fun report(progress: CloudUploadProgress?) {
        _progress.value = progress
    }

    internal fun finished(result: CloudUploadResult) {
        _progress.value = null
        _last.value = result
    }

    companion object {
        const val WORK_NAME = "cloud_upload"
        internal const val KEY_PATH = "path"
    }
}

/**
 * Sends one local song to Tryptify Cloud. A worker that fails would fail every
 * song queued after it in the chain, so a song that cannot be sent is reported
 * through [CloudUploads.last] and the worker still succeeds; only a dropped
 * connection is retried, from the start of the file (the server has already
 * aborted the half-sent upload and given its reservation back).
 */
@HiltWorker
class CloudUploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val cloud: CloudRepository,
    private val dao: LocalMediaDao,
    private val uploads: CloudUploads,
) : CoroutineWorker(context, params) {

    private var title: String = ""

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f)

    override suspend fun doWork(): Result {
        val path = inputData.getString(CloudUploads.KEY_PATH) ?: return Result.success()
        val file = File(path)
        val track = if ("://" in path) null else dao.findByPath(path)
        title = track?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        if (track == null || !file.canRead()) {
            uploads.finished(CloudUploadResult(title, CloudError.Server("unreadable")))
            return Result.success()
        }
        runCatching { setForeground(foregroundInfo(0f)) }
        val total = file.length()
        uploads.report(CloudUploadProgress(title, 0, total))
        return try {
            cloud.upload(file, file.extension.lowercase(), track.toMeta(file), coverOf(track)) { sent ->
                val p = CloudUploadProgress(title, sent, total)
                uploads.report(p)
                runCatching { setForeground(foregroundInfo(p.fraction)) }
            }
            uploads.finished(CloudUploadResult(title, null))
            Result.success()
        } catch (e: CancellationException) {
            uploads.report(null)
            throw e
        } catch (e: CloudError) {
            if (e is CloudError.Network && runAttemptCount < MAX_RETRIES) {
                uploads.report(null)
                Result.retry()
            } else {
                uploads.finished(CloudUploadResult(title, e))
                Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                uploads.report(null)
                Result.retry()
            } else {
                uploads.finished(CloudUploadResult(title, CloudError.Network(e)))
                Result.success()
            }
        }
    }

    private fun foregroundInfo(fraction: Float): ForegroundInfo {
        createChannel()
        val text = AppLanguage.wrap(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(text.getString(R.string.cloud_uploading))
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val text = AppLanguage.wrap(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.cloud_uploads_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = text.getString(R.string.cloud_uploads_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "cloud_uploads"
        private const val NOTIFICATION_ID = 8110
        private const val MAX_RETRIES = 3
    }
}

internal fun LocalTrackEntity.toMeta(file: File) = CloudSongMeta(
    title = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
    artist = artist?.takeIf { it.isNotBlank() },
    album = album?.takeIf { it.isNotBlank() },
    albumArtist = albumArtist?.takeIf { it.isNotBlank() },
    track = trackNumber?.takeIf { it > 0 },
    disc = discNumber?.takeIf { it > 0 },
    year = year?.takeIf { it > 0 },
    genre = genre?.takeIf { it.isNotBlank() },
    duration = durationSeconds.coerceAtLeast(0),
    bitRate = bitRate.takeIf { it > 0 },
    sampleRate = sampleRate.takeIf { it > 0 },
    bitDepth = bitDepth?.takeIf { it > 0 },
)

/**
 * The track's cached cover, when it is a picture the server takes. The cache
 * key can fall back to the audio file itself, which is never a cover.
 */
internal fun coverOf(track: LocalTrackEntity): File? {
    val key = track.artworkCacheKey ?: return null
    if (key == track.filePath || "://" in key) return null
    if (key.substringAfterLast('.').lowercase() !in COVER_TYPES) return null
    return File(key).takeIf { it.isFile && it.length() in 1..CloudRepository.MAX_COVER_BYTES }
}

private val COVER_TYPES = setOf("jpg", "jpeg", "png", "webp")

/** The upload queue for the app's root, which hands it to every song menu. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface CloudEntryPoint {
    fun cloudUploads(): CloudUploads
}
