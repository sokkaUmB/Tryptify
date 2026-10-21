package tf.monochrome.android.audio

import android.content.Context
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
 * On Android 14+ (API 34+), also configures [AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]
 * via [AudioManager.setPreferredMixerAttributes] for native bit-perfect output without
 * requiring developer options ("Disable USB audio routing").
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

    private var activeBitPerfectDevice: AudioDeviceInfo? = null
    private val _isBitPerfectActive = MutableStateFlow(false)
    val isBitPerfectActive: StateFlow<Boolean> = _isBitPerfectActive.asStateFlow()

    private val mediaAudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
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
        val current = currentUsbOutput()
        _usbOutputDevice.value = current
        if (current == null) {
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

            val targetAttr = findBestBitPerfectAttribute(supported, sampleRate, encoding)
            if (targetAttr != null) {
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
        val bitPerfectList = supported.filter {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }

        if (bitPerfectList.isNotEmpty()) {
            if (requestedSampleRate > 0) {
                val exact = bitPerfectList.firstOrNull { it.format.sampleRate == requestedSampleRate }
                if (exact != null) return exact
            }
            return bitPerfectList.first()
        }

        val targetRate = if (requestedSampleRate > 0) requestedSampleRate else 48000
        val targetEncoding = when (requestedEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            AudioFormat.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(targetRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(targetEncoding)
            .build()

        return AudioMixerAttributes.Builder(audioFormat)
            .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
            .build()
    }

    private companion object {
        const val TAG = "UsbAudioRouter"
    }
}
