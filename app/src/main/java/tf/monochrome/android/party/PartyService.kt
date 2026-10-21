// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tf.monochrome.android.R

/**
 * Keeps this phone at the listening party while the app is in the
 * background: a foreground service for as long as a party is on, playing or
 * not, with its quiet notification, a wake lock and a Wi-Fi lock.
 *
 * Without it the party's socket lived only as long as Android let the app
 * run. The music player is a foreground service only while it plays, so a
 * phone that went to another app could be frozen or cut off: the server saw
 * the host away, and handed the host's seat on. It holds no state of its own;
 * [PartyController] starts it when a party begins and stops it when it ends.
 */
class PartyService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID, notification)
            }
        }.onFailure {
            // Started from the background, which Android refuses: the party
            // carries on as before, only less sure of the background.
            Log.w(TAG, "party service: ${it.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tryptify:party")
                .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tryptify:party")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        wake = null
        wifi = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.party_service_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0x9A8, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.listening_party))
            .setContentText(getString(R.string.party_service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "PartyService"
        private const val CHANNEL_ID = "listening_party_on"
        private const val ID = 0x9A80

        /** A party longer than this lets the phone sleep; a wake lock is never held for good. */
        private const val MAX_HOLD_MS = 12 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PartyService::class.java)) }
                .onFailure { Log.w(TAG, "party service not started: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PartyService::class.java))
        }
    }
}
