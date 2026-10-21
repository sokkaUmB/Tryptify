// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A speed, and whether it keeps the pitch; false is vinyl style, the pitch riding the speed. */
data class SpeedSetting(val speed: Float, val keepPitch: Boolean)

/**
 * A speed this phone plays at instead of the listener's own setting: the
 * listening party's, while the phone is at one and someone else is the host.
 * The setting itself is never touched, so it is back as it was the moment the
 * override is cleared. [PlaybackService] is the one place either reaches the
 * player.
 */
@Singleton
class SpeedOverride @Inject constructor() {
    private val _value = MutableStateFlow<SpeedSetting?>(null)
    val value: StateFlow<SpeedSetting?> = _value.asStateFlow()

    fun set(value: SpeedSetting?) {
        _value.value = value
    }
}
