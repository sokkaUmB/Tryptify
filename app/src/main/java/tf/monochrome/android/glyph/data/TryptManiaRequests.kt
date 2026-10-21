// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A song asked for from a song's menu anywhere in the app: "Play in Trypt
 * Mania". What the menu knows of it, for Trypt Mania to find its file by.
 */
data class TryptManiaRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    /** The song's id in its catalogue, which its download is saved under. */
    val catalogTrackId: Long? = null,
    /** Its file, where the list showing it knows one (the library's own songs). */
    val filePath: String? = null,
)

/**
 * The hand-off between a song's menu and Trypt Mania: the menu leaves the
 * request here and opens the mode, whose screen takes it once it is up. One
 * at a time; a second asked before the first is taken replaces it.
 */
object TryptManiaRequests {
    private val _pending = MutableStateFlow<TryptManiaRequest?>(null)
    val pending: StateFlow<TryptManiaRequest?> = _pending.asStateFlow()

    fun send(request: TryptManiaRequest) {
        _pending.value = request
    }

    /** The request waiting, now taken; null when there is none. */
    fun take(): TryptManiaRequest? = _pending.getAndUpdate { null }

    private val _syncPending = MutableStateFlow(false)

    /**
     * Settings › Audio's Measure: the mode opens on its Sync tab, the tap test
     * that measures how late the output plays, and leaves when it is closed.
     */
    val syncPending: StateFlow<Boolean> = _syncPending.asStateFlow()

    fun openSync() {
        _syncPending.value = true
    }

    /** Whether Sync was asked for, now taken. */
    fun takeSync(): Boolean = _syncPending.getAndUpdate { false }
}
