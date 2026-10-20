package tf.monochrome.android.audio.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.eq.OutputEqSwitcher
import tf.monochrome.android.audio.eq.OutputId
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The output playing now and how late it plays ([OutputSync]), for everything
 * that follows the music, and the one place a new measurement is saved.
 */
@Singleton
class OutputSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    switcher: OutputEqSwitcher,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The output playing now, or null for one with no slot (HDMI, a call): no delay. */
    val output: StateFlow<OutputId?> = switcher.current

    /** Every output's delay, by [OutputId.key]. */
    val delays: StateFlow<Map<String, Int>> =
        preferences.outputSyncDelays.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** How late the output playing now plays, in milliseconds. */
    val delayMs: StateFlow<Int> =
        combine(output, delays, OutputSync::delayFor).stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch { migrateOnce() }
    }

    /** Saves [ms] for the output playing now; nothing when there is none. */
    fun setForCurrent(ms: Int) {
        val out = output.value ?: return
        scope.launch { preferences.updateOutputSyncDelays { OutputSync.set(it, out, ms) } }
    }

    private suspend fun migrateOnce() {
        if (preferences.outputSyncMigrated.first()) return
        val trypt = context.getSharedPreferences(TRYPT_MANIA_PREFS, Context.MODE_PRIVATE)
            .getInt(TRYPT_MANIA_OFFSET, 0)
        val lyrics = preferences.lyricsFx.first().bluetoothDelayMs
        val visualizer = preferences.visualizerAudioDelayMs.first()
        val carried = OutputSync.migrate(trypt, lyrics, visualizer)
        preferences.updateOutputSyncDelays { now -> if (now.isEmpty()) carried else now }
        preferences.setOutputSyncMigrated()
    }

    private companion object {
        // Trypt Mania's own preferences (GlyphViewModel), read once to carry its offset over.
        const val TRYPT_MANIA_PREFS = "trypt_mania"
        const val TRYPT_MANIA_OFFSET = "audio_offset_ms"
    }
}
