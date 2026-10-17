// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import tf.monochrome.android.R

/**
 * Drains [ServerLyricsEngine]'s line, one song at a time, under a
 * notification, as [StepManiaGenerationWorker][tf.monochrome.android.audio.stepmania.StepManiaGenerationWorker]
 * drains the chart queue: the song, its stage (downloading, sending,
 * hearing) while it runs, and a note that can be swiped away when the line
 * is done, saying which songs got their words.
 */
@HiltWorker
class LyricsSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: ServerLyricsEngine,
) : CoroutineWorker(context, params) {

    private var current: Pair<String, LyricsStage>? = null

    override suspend fun doWork(): Result {
        if (!engine.hasWork()) return Result.success()
        val outcomes = mutableListOf<LyricsOutcome>()
        try {
            while (true) {
                val job = engine.next() ?: break
                show(job.track.title, LyricsStage.SENDING)
                outcomes += engine.run(job) { stage -> show(job.track.title, stage) }
            }
        } finally {
            notifyFinished(outcomes)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun show(title: String, stage: LyricsStage) {
        current = title to stage
        // A foreground start can be refused from the background on Android
        // 12+; the work goes on without its notification then.
        runCatching { setForegroundAsync(foregroundInfo()) }
    }

    private fun foregroundInfo(): ForegroundInfo {
        createChannel(applicationContext)
        val (title, stage) = current ?: ("a song" to LyricsStage.SENDING)
        val text = when (stage) {
            LyricsStage.DOWNLOADING -> "Downloading the song first"
            LyricsStage.SENDING -> "Sending the song to the server"
            LyricsStage.HEARING -> "The server is hearing every word"
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Timing lyrics: $title")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, 0, true)
            .build()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** How the line ended, for a listener who has long since left the lyrics. Swiped away like any note. */
    private fun notifyFinished(outcomes: List<LyricsOutcome>) {
        val (heading, text) = finishedMessage(outcomes) ?: return
        val context = applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun openApp(): PendingIntent? {
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(
            applicationContext,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val WORK_NAME = "lyrics_timing"
        private const val CHANNEL_ID = "lyrics_timing"
        private const val NOTIFICATION_ID = 8300
        private const val DONE_NOTIFICATION_ID = 8301

        /** The heading and text of the note left when the line is done; null when nothing was done. */
        fun finishedMessage(outcomes: List<LyricsOutcome>): Pair<String, String>? {
            if (outcomes.isEmpty()) return null
            val made = outcomes.filter { it.failure == null }
            val failed = outcomes.filter { it.failure != null }
            if (outcomes.size == 1) {
                val one = outcomes.single()
                return if (one.failure == null) {
                    "Lyrics ready" to "${one.title}: every word has its time."
                } else {
                    "Lyrics not timed" to "${one.title}: ${one.failure}."
                }
            }
            val heading = when {
                failed.isEmpty() -> "${made.size} songs' lyrics ready"
                made.isEmpty() -> "No lyrics timed"
                else -> "${made.size} of ${outcomes.size} songs' lyrics ready"
            }
            val text = buildString {
                if (made.isNotEmpty()) append(made.joinToString(", ") { it.title }).append('.')
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("Not timed: ").append(failed.joinToString("; ") { "${it.title} (${it.failure})" }).append('.')
                }
            }
            return heading to text
        }

        /** Start a worker on the line, after any one still finishing. */
        fun start(context: Context) {
            val work = OneTimeWorkRequestBuilder<LyricsSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lyrics timing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of lyrics being timed on your server in the background"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }
}
