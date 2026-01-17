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
import android.os.HandlerThread
import android.os.Looper
import com.twilio.audioswitch.AbstractAudioSwitch
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import com.twilio.audioswitch.AudioSwitch
import com.twilio.audioswitch.LegacyAudioSwitch
import io.livekit.android.room.Room
import io.livekit.android.util.LKLog
import java.util.Collections
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
    @Deprecated("Use registerAudioDeviceChangeListener.")
    var audioDeviceChangeListener: AudioDeviceChangeListener? = null

    private val audioDeviceChangeListeners = Collections.synchronizedSet(mutableSetOf<AudioDeviceChangeListener>())

    private val audioDeviceChangeDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        object : AudioDeviceChangeListener {
            override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) {
                @Suppress("DEPRECATION")
                audioDeviceChangeListener?.invoke(audioDevices, selectedAudioDevice)
                synchronized(audioDeviceChangeListeners) {
                    for (listener in audioDeviceChangeListeners) {
                        listener.invoke(audioDevices, selectedAudioDevice)
                    }
                }
            }
        }
    }

    /**
     * Listen to changes in audio focus.
     *
     * @see AudioManager.OnAudioFocusChangeListener
     */
    @Deprecated("Use registerOnAudioFocusChangeListener.")
    var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    private val onAudioFocusChangeListeners = Collections.synchronizedSet(mutableSetOf<AudioManager.OnAudioFocusChangeListener>())

    private val onAudioFocusChangeDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            @Suppress("DEPRECATION")
            onAudioFocusChangeListener?.onAudioFocusChange(focusChange)
            synchronized(onAudioFocusChangeListeners) {
                for (listener in onAudioFocusChangeListeners) {
                    listener.onAudioFocusChange(focusChange)
                }
            }
        }
    }

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
     * Defaults to [AudioManager.MODE_NORMAL] for media playback.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioMode: Int = AudioManager.MODE_NORMAL

    /**
     * The audio focus mode to use while started.
     *
     * Defaults to [AudioManager.AUDIOFOCUS_GAIN].
     */
    var focusMode: Int = AudioManager.AUDIOFOCUS_GAIN

    /**
     * The audio stream type to use when requesting audio focus on pre-O devices.
     *
     * Defaults to [AudioManager.STREAM_MUSIC] for media playback.
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioStreamType: Int = AudioManager.STREAM_MUSIC

    /**
     * The audio attribute usage type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.USAGE_MEDIA] for media playback.
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioAttributeUsageType: Int = AudioAttributes.USAGE_MEDIA

    /**
     * The audio attribute content type to use when requesting audio focus on devices O and beyond.
     *
     * Defaults to [AudioAttributes.CONTENT_TYPE_MUSIC] for media playback.
     *
     * Refer to this [compatibility table](https://source.android.com/docs/core/audio/attributes#compatibility)
     * to ensure that your values match between android versions.
     *
     * Note: Manual audio routing may not work appropriately when using non-default values.
     */
    var audioAttributeContentType: Int = AudioAttributes.CONTENT_TYPE_MUSIC

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

    // AudioSwitch is not threadsafe, so all calls should be done through a single thread.
    private var handler: Handler? = null
    private var thread: HandlerThread? = null

    @Synchronized
    override fun start() {
        if (handler != null || thread != null) {
            LKLog.i { "AudioSwitchHandler called start multiple times?" }
        }

        if (thread == null) {
            thread = HandlerThread("AudioSwitchHandlerThread").also { it.start() }
        }
        if (handler == null) {
            handler = Handler(thread!!.looper)
        }

        if (audioSwitch == null) {
            handler?.removeCallbacksAndMessages(null)
            handler?.postAtFrontOfQueue {
                val switch =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AudioSwitch(
                            context = context,
                            loggingEnabled = loggingEnabled,
                            audioFocusChangeListener = onAudioFocusChangeDispatcher,
                            preferredDeviceList = preferredDeviceList ?: defaultPreferredDeviceList,
                        )
                    } else {
                        LegacyAudioSwitch(
                            context = context,
                            loggingEnabled = loggingEnabled,
                            audioFocusChangeListener = onAudioFocusChangeDispatcher,
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
                switch.start(audioDeviceChangeDispatcher)
                switch.activate()
            }
        }
    }

    @Synchronized
    override fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler?.postAtFrontOfQueue {
            audioSwitch?.stop()
            audioSwitch = null
        }
        thread?.quitSafely()

        handler = null
        thread = null
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
    @Synchronized
    fun selectDevice(audioDevice: AudioDevice?) {
        if (Looper.myLooper() == handler?.looper) {
            audioSwitch?.selectDevice(audioDevice)
        } else {
            handler?.post {
                audioSwitch?.selectDevice(audioDevice)
            }
        }
    }

    /**
     * Listen to changes in the available and active audio devices.
     * @see unregisterAudioDeviceChangeListener
     */
    fun registerAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        audioDeviceChangeListeners.add(listener)
    }

    /**
     * Remove a previously registered audio device change listener.
     * @see registerAudioDeviceChangeListener
     */
    fun unregisterAudioDeviceChangeListener(listener: AudioDeviceChangeListener) {
        audioDeviceChangeListeners.remove(listener)
    }

    /**
     * Listen to changes in audio focus.
     *
     * @see AudioManager.OnAudioFocusChangeListener
     * @see unregisterOnAudioFocusChangeListener
     */
    fun registerOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener) {
        onAudioFocusChangeListeners.add(listener)
    }

    /**
     * Remove a previously registered focus change listener.
     *
     * @see AudioManager.OnAudioFocusChangeListener
     * @see registerOnAudioFocusChangeListener
     */
    fun unregisterOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener) {
        onAudioFocusChangeListeners.remove(listener)
    }

    companion object {
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
