package tf.monochrome.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioMixerAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently-attached USB Audio Class DAC (if any) so the
 * ExoPlayer instance in PlaybackService can be pinned to it via
 * `setPreferredAudioDevice` when the user has bit-perfect routing on.
 *
 * Also provides Android 14+ (API 34+) bit-perfect mixer attribute control
 * via `AudioManager.setPreferredMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT`.
 */
@Singleton
class UsbAudioRouter @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val callbackHandler = Handler(Looper.getMainLooper())

    private val _usbOutputDevice = MutableStateFlow<AudioDeviceInfo?>(initialUsbDevice())
    val usbOutputDevice: StateFlow<AudioDeviceInfo?> = _usbOutputDevice.asStateFlow()

    private val _isBitPerfectActive = MutableStateFlow(false)
    val isBitPerfectActive: StateFlow<Boolean> = _isBitPerfectActive.asStateFlow()

    private var activeBitPerfectDevice: AudioDeviceInfo? = null

    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    refresh()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    refresh()
                }
            },
            callbackHandler,
        )
    }

    private fun refresh() {
        val dev = currentUsbOutput()
        _usbOutputDevice.value = dev
        if (dev == null && activeBitPerfectDevice != null) {
            clearBitPerfectAttributes()
        }
    }

    private fun initialUsbDevice(): AudioDeviceInfo? = currentUsbOutput()

    private fun currentUsbOutput(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Prefer USB_HEADSET (DAC + headphone amp combo, most external
        // dongles) over generic USB_DEVICE; if both exist, headset wins.
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
    }

    /** Human-readable label for the Settings screen. */
    fun describe(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: when (device.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
                else -> "USB Output"
            }

    /**
     * Applies bit-perfect mixer attributes to [device] on Android 14+ (API 34+).
     */
    fun applyBitPerfectAttributes(
        device: AudioDeviceInfo?,
        sampleRate: Int = 0,
        encoding: Int = 0,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (device == null) {
            clearBitPerfectAttributes()
            return false
        }

        try {
            val supported = audioManager.getSupportedMixerAttributes(device)
            Log.i(TAG, "Supported mixer attributes for ${device.productName} (count=${supported.size})")
            for (attr in supported) {
                Log.d(TAG, "  supported attr: rate=${attr.format.sampleRate} enc=${attr.format.encoding} ch=${attr.format.channelMask} behavior=${attr.mixerBehavior}")
            }

            val targetAttr = findBestBitPerfectAttribute(supported, sampleRate, encoding)
            if (targetAttr != null) {
                val currentPreferred = audioManager.getPreferredMixerAttributes(mediaAudioAttributes, device)
                if (currentPreferred != null &&
                    currentPreferred.format.sampleRate == targetAttr.format.sampleRate &&
                    currentPreferred.format.encoding == targetAttr.format.encoding &&
                    currentPreferred.mixerBehavior == targetAttr.mixerBehavior
                ) {
                    activeBitPerfectDevice = device
                    _isBitPerfectActive.value = (targetAttr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    return true
                }

                val ok = audioManager.setPreferredMixerAttributes(mediaAudioAttributes, device, targetAttr)
                Log.i(
                    TAG,
                    "setPreferredMixerAttributes: rate=${targetAttr.format.sampleRate}, " +
                        "encoding=${targetAttr.format.encoding}, " +
                        "behavior=${targetAttr.mixerBehavior} -> $ok",
                )
                if (ok) {
                    activeBitPerfectDevice = device
                    _isBitPerfectActive.value = (targetAttr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    return ok
                }
            } else {
                Log.w(TAG, "No suitable bit-perfect mixer attribute found for rate=$sampleRate encoding=$encoding")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting preferred mixer attributes on ${device.productName}", e)
        }
        return false
    }

    /**
     * Clears preferred mixer attributes on the active bit-perfect device.
     */
    fun clearBitPerfectAttributes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val dev = activeBitPerfectDevice ?: return
        try {
            val ok = audioManager.clearPreferredMixerAttributes(mediaAudioAttributes, dev)
            Log.i(TAG, "clearPreferredMixerAttributes for ${dev.productName} -> $ok")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing preferred mixer attributes", e)
        } finally {
            activeBitPerfectDevice = null
            _isBitPerfectActive.value = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun findBestBitPerfectAttribute(
        supported: List<AudioMixerAttributes>,
        requestedSampleRate: Int,
        requestedEncoding: Int,
    ): AudioMixerAttributes? {
        if (supported.isEmpty()) return null

        // 1. Exact match for both sample rate and encoding
        if (requestedSampleRate > 0) {
            val exactBoth = supported.firstOrNull {
                it.format.sampleRate == requestedSampleRate && it.format.encoding == requestedEncoding
            }
            if (exactBoth != null) {
                return AudioMixerAttributes.Builder(exactBoth.format)
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()
            }

            // 2. Exact sample rate match with higher bit depth (e.g. 16-bit source on 24-bit DAC)
            // Mathematically bit-perfect via lossless zero-extension
            val exactRate = supported.firstOrNull { it.format.sampleRate == requestedSampleRate }
            if (exactRate != null) {
                return AudioMixerAttributes.Builder(exactRate.format)
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()
            }

            // 3. Same clock family (44.1k family or 48k family)
            val sameFamily = supported.filter {
                val rate = it.format.sampleRate
                val is44Family = (requestedSampleRate % 44100 == 0)
                if (is44Family) (rate % 44100 == 0) else (rate % 48000 == 0)
            }
            val bestFamily = sameFamily.minByOrNull { kotlin.math.abs(it.format.sampleRate - requestedSampleRate) }
            if (bestFamily != null) {
                return AudioMixerAttributes.Builder(bestFamily.format)
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()
            }
        }

        // 4. Closest overall hardware rate
        val candidate = if (requestedSampleRate > 0) {
            supported.minByOrNull { kotlin.math.abs(it.format.sampleRate - requestedSampleRate) } ?: supported.first()
        } else {
            supported.first()
        }

        return AudioMixerAttributes.Builder(candidate.format)
            .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            .build()
    }

    private companion object {
        const val TAG = "UsbAudioRouter"
    }
}
