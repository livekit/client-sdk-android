/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.twilio.audioswitch.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [AudioHandler] built on top of [AudioSwitch]. This handles things such as
 * getting the audio focus as needed, as well as automatic audio output device management.
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
     * 3. Speakerphone
     * 4. Earpiece
     */
    var preferredDeviceList: List<Class<out AudioDevice>>? = null

    /**
     * When true, AudioSwitchHandler will request audio focus on start and abandon on stop.
     *
     * Defaults to true.
     */
    var manageAudioFocus = true

    /**
     * The audio mode to use when requesting audio focus.
     *
     * Defaults to [AudioManager.MODE_IN_COMMUNICATION].
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioMode: Int = AudioManager.MODE_IN_COMMUNICATION

    /**
     * The audio focus mode to use while started.
     *
     * Defaults to [AudioManager.AUDIOFOCUS_GAIN].
     */
    var focusMode: Int = AudioManager.AUDIOFOCUS_GAIN

    /**
     * The audio stream type to use when requesting audio focus on pre-O devices.
     *
     * Defaults to [AudioManager.STREAM_VOICE_CALL].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioStreamType: Int = AudioManager.STREAM_VOICE_CALL

    /**
     * The audio attribute usage type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.USAGE_VOICE_COMMUNICATION].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioAttributeUsageType: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION

    /**
     * The audio attribute content type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.CONTENT_TYPE_SPEECH].
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioAttributeContentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH

    /**
     * On certain Android devices, audio routing does not function properly and bluetooth microphones will not work
     * unless audio mode is set to [AudioManager.MODE_IN_COMMUNICATION] or [AudioManager.MODE_IN_CALL].
     *
     * AudioSwitchHandler by default will not handle audio routing in those cases to avoid audio issues.
     *
     * If this set to true, AudioSwitchHandler will attempt to do audio routing, though behavior is undefined.
     */
    var forceHandleAudioRouting = false

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
                            preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList,
                        )
                    } else {
                        LegacyAudioSwitch(
                            context = context,
                            loggingEnabled = loggingEnabled,
                            audioFocusChangeListener = onAudioFocusChangeListener ?: defaultOnAudioFocusChangeListener,
                            preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList,
                        )
                    }
                switch.manageAudioFocus = manageAudioFocus
                switch.audioMode = audioMode
                switch.focusMode = focusMode
                switch.audioStreamType = audioStreamType
                switch.audioAttributeUsageType = audioAttributeUsageType
                switch.audioAttributeContentType = audioAttributeContentType
                switch.forceHandleAudioRouting = forceHandleAudioRouting

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

    /**
     * The currently selected audio device, or null if none (or this handler is not started).
     */
    val selectedAudioDevice: AudioDevice?
        get() = audioSwitch?.selectedAudioDevice

    /**
     * The available audio devices. This requires calling [start] before it is populated.
     */
    val availableAudioDevices: List<AudioDevice>
        get() = audioSwitch?.availableAudioDevices ?: listOf()

    /**
     * Select a specific audio device.
     */
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
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            )
        }
    }
}
