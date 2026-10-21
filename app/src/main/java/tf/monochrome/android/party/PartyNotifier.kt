// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.party

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.R

/**
 * The party's own settings: whether songs this phone adds are shared, and
 * which of the party's goings-on are notified. Kept on this phone alone.
 */
@Singleton
class PartyPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("listening_party", Context.MODE_PRIVATE)

    private val _shareSongs = MutableStateFlow(store.getBoolean(SHARE, true))
    val shareSongs: StateFlow<Boolean> = _shareSongs.asStateFlow()

    private val _notify = MutableStateFlow(
        PartyNotify(
            chat = store.getBoolean(NOTIFY_CHAT, true),
            joins = store.getBoolean(NOTIFY_JOINS, true),
            songs = store.getBoolean(NOTIFY_SONGS, true),
        ),
    )
    val notify: StateFlow<PartyNotify> = _notify.asStateFlow()

    fun setShareSongs(on: Boolean) {
        store.edit().putBoolean(SHARE, on).apply()
        _shareSongs.value = on
    }

    fun setNotify(value: PartyNotify) {
        store.edit()
            .putBoolean(NOTIFY_CHAT, value.chat)
            .putBoolean(NOTIFY_JOINS, value.joins)
            .putBoolean(NOTIFY_SONGS, value.songs)
            .apply()
        _notify.value = value
    }

    private companion object {
        const val SHARE = "share_songs"
        const val NOTIFY_CHAT = "notify_chat"
        const val NOTIFY_JOINS = "notify_joins"
        const val NOTIFY_SONGS = "notify_songs"
    }
}

/** Which of the party's goings-on are notified, each switched on its own. */
data class PartyNotify(val chat: Boolean = true, val joins: Boolean = true, val songs: Boolean = true)

/**
 * Notifications for the party ("Listening party" channel): someone said
 * something, someone joined, a song went in the queue. Never for this
 * phone's own lines, never while the party screen is on show, and each kind
 * as the listener switched it. One notification a kind, replaced as the next
 * comes, so a lively chat does not stack a hundred of them.
 */
@Singleton
class PartyNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PartyPrefs,
) {
    @Volatile
    private var shown = false

    fun screenShown(on: Boolean) {
        shown = on
        if (on) clear()
    }

    fun line(line: PartyLine, myId: String?) {
        if (shown || line.id == myId) return
        val notify = prefs.notify.value
        val (on, id, text) = when (line.kind) {
            PartyLineKind.SAY -> Triple(notify.chat, ID_CHAT, context.getString(R.string.party_notify_said, line.name, line.text))
            PartyLineKind.JOIN -> Triple(
                notify.joins, ID_JOINS,
                context.getString(if (line.text == "web") R.string.party_line_joined_web else R.string.party_line_joined, line.name),
            )
            PartyLineKind.ADD -> Triple(notify.songs, ID_SONGS, context.getString(R.string.party_line_added, line.name, line.text))
            else -> return
        }
        if (!on || !allowed()) return
        createChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.listening_party))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .build()
        manager().notify(id, notification)
    }

    fun clear() {
        listOf(ID_CHAT, ID_JOINS, ID_SONGS).forEach { manager().cancel(it) }
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.listening_party), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0x9A7, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "listening_party"
        private const val ID_CHAT = 0x9A71
        private const val ID_JOINS = 0x9A72
        private const val ID_SONGS = 0x9A73
    }
}
