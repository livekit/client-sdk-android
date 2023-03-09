package io.livekit.android.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilio.audioswitch.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AudioHandler] built on top of [AudioSwitch].
 *
 * The various settings should be set before connecting to a [Room] and [start] is called.
 */
@Singleton
class AudioSwitchHandler
@Inject
constructor(private val context: Context) : AudioHandler {
    /**
     * Toggle whether logging is enabled for [AudioSwitch]. By default, this is set to false.
     */
    var loggingEnabled = false

    /**
     * Listen to changes in the available and active audio devices.
     *
     * @see AudioDeviceChangeListener
     */
    var audioDeviceChangeListener: AudioDeviceChangeListener? = null

    /**
     * Listen to changes in audio focus.
     *
     * @see AudioManager.OnAudioFocusChangeListener
     */
    var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * The preferred priority of audio devices to use. The first available audio device will be used.
     *
     * By default, the preferred order is set to:
     * 1. BluetoothHeadset
     * 2. WiredHeadset
     * 3. Earpiece
     * 4. Speakerphone
     */
    var preferredDeviceList: List<Class<out AudioDevice>>? = null

    private var audioSwitch: AbstractAudioSwitch? = null

    // AudioSwitch is not threadsafe, so all calls should be done on the main thread.
    private val handler = Handler(Looper.getMainLooper())

    override fun start() {
        if (audioSwitch == null) {
            handler.removeCallbacksAndMessages(null)
            handler.postAtFrontOfQueue {
                val switch =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AudioSwitch(
                            context = context,
                            loggingEnabled = loggingEnabled,
                            audioFocusChangeListener = onAudioFocusChangeListener ?: defaultOnAudioFocusChangeListener,
                            preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList
                        )
                    } else {
                        LegacyAudioSwitch(
                            context = context,
                            loggingEnabled = loggingEnabled,
                            audioFocusChangeListener = onAudioFocusChangeListener ?: defaultOnAudioFocusChangeListener,
                            preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList
                        )
                    }
                audioSwitch = switch
                switch.start(audioDeviceChangeListener ?: defaultAudioDeviceChangeListener)
                switch.activate()
            }
        }
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        handler.postAtFrontOfQueue {
            audioSwitch?.stop()
            audioSwitch = null
        }
    }

    val selectedAudioDevice: AudioDevice?
        get() = audioSwitch?.selectedAudioDevice

    val availableAudioDevices: List<AudioDevice>
        get() = audioSwitch?.availableAudioDevices ?: listOf()

    fun selectDevice(audioDevice: AudioDevice?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            audioSwitch?.selectDevice(audioDevice)
        } else {
            handler.post {
                audioSwitch?.selectDevice(audioDevice)
            }
        }
    }

    companion object {
        private val defaultOnAudioFocusChangeListener by lazy(LazyThreadSafetyMode.NONE) {
            AudioManager.OnAudioFocusChangeListener { }
        }
        private val defaultAudioDeviceChangeListener by lazy(LazyThreadSafetyMode.NONE) {
            object : AudioDeviceChangeListener {
                override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) {
                }
            }
        }
        private val defaultPreferredDeviceList by lazy(LazyThreadSafetyMode.NONE) {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java
            )
        }
    }
}