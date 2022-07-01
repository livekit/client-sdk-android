package io.livekit.android.audio

import android.content.Context
import android.media.AudioManager
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import com.twilio.audioswitch.AudioSwitch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioSwitchHandler
@Inject
constructor(private val context: Context) : AudioHandler {
    var loggingEnabled = false
    var audioDeviceChangeListener: AudioDeviceChangeListener? = null
    var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    var preferredDeviceList: List<Class<out AudioDevice>>? = null

    private var audioSwitch: AudioSwitch? = null

    override fun start() {
        if (audioSwitch == null) {
            val switch = AudioSwitch(
                context = context,
                loggingEnabled = loggingEnabled,
                audioFocusChangeListener = onAudioFocusChangeListener ?: defaultOnAudioFocusChangeListener,
                preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList
            )
            audioSwitch = switch
            switch.start(audioDeviceChangeListener ?: defaultAudioDeviceChangeListener)
            switch.activate()
        }
    }

    override fun stop() {
        audioSwitch?.stop()
        audioSwitch = null
    }

    val selectedAudioDevice: AudioDevice?
        get() = audioSwitch?.selectedAudioDevice

    val availableAudioDevices: List<AudioDevice>
        get() = audioSwitch?.availableAudioDevices ?: listOf()

    fun selectDevice(audioDevice: AudioDevice?) {
        audioSwitch?.selectDevice(audioDevice)
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