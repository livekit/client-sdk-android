/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import io.livekit.android.audio.AudioFocusHandler
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.room.Room
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoDecoderFactory
import livekit.org.webrtc.VideoEncoderFactory
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import okhttp3.OkHttpClient
/**
 * Overrides to replace LiveKit internally used components with custom implementations.
 */
data class LiveKitOverrides(
    /**
     * Override the [OkHttpClient] used by the library.
     */
    val okHttpClient: OkHttpClient? = null,

    /**
     * Override the [VideoEncoderFactory] used by the library.
     */
    val videoEncoderFactory: VideoEncoderFactory? = null,

    /**
     * Override the [VideoDecoderFactory] used by the library.
     */
    val videoDecoderFactory: VideoDecoderFactory? = null,

    /**
     * Override various audio options used by the library.
     */
    val audioOptions: AudioOptions? = null,

    /**
     * Override the [EglBase] used by the library.
     *
     * If a non-null value is passed, the library does not
     * take ownership of the object and will not release it upon [Room.release].
     * It is the responsibility of the owner to call [EglBase.release] when finished
     * with it to prevent memory leaks.
     */
    val eglBase: EglBase? = null,

    /**
     * Override the options passed into the PeerConnectionFactory when building it.
     */
    val peerConnectionFactoryOptions: PeerConnectionFactory.Options? = null,
)

/**
 * Options for customizing the audio settings of LiveKit.
 */
class AudioOptions(
    /**
     * Override the default output [AudioType].
     *
     * This affects the audio routing and how the audio is handled. Default is [AudioType.CallAudioType].
     *
     * Note: if [audioHandler] is also passed, the values from [audioOutputType] will not be reflected in it,
     * and must be set yourself.
     */
    val audioOutputType: AudioType? = null,
    /**
     * Override the default [AudioHandler].
     *
     * Default is [AudioSwitchHandler].
     *
     * Use [NoAudioHandler] to turn off automatic audio handling or
     * [AudioFocusHandler] to get simple audio focus handling.
     */
    val audioHandler: AudioHandler? = null,

    /**
     * Override the default [AudioDeviceModule].
     *
     * If a non-null value is passed, the library does not
     * take ownership of the object and will not release it upon [Room.release].
     * It is the responsibility of the owner to call [AudioDeviceModule.release] when finished
     * with it to prevent memory leaks.
     */
    val audioDeviceModule: AudioDeviceModule? = null,

    /**
     * Called after default setup to allow for customizations on the [JavaAudioDeviceModule].
     *
     * Not used if [audioDeviceModule] is provided.
     */
    val javaAudioDeviceModuleCustomizer: ((builder: JavaAudioDeviceModule.Builder) -> Unit)? = null,

    /**
     * On Android 11+, the audio mode will reset itself from [AudioManager.MODE_IN_COMMUNICATION] if
     * there is no audio playback or capture for 6 seconds (for example when joining a room with
     * no speakers and the local mic is muted.) This mode reset will cause unexpected
     * behavior when trying to change the volume, causing it to not properly change the volume.
     *
     * We use a workaround by playing a silent audio track to keep the communication mode from
     * resetting.
     *
     * Setting this flag to true will disable the workaround.
     *
     * This flag is a no-op when the audio mode is set to anything other than
     * [AudioManager.MODE_IN_COMMUNICATION].
     */
    val disableCommunicationModeWorkaround: Boolean = false,

    /**
     * Options for processing the mic and incoming audio.
     */
    val audioProcessorOptions: AudioProcessorOptions? = null,
)

/**
 * Audio types for customizing the audio of LiveKit.
 */
sealed class AudioType(
    /**
     * The audio mode to use when playing audio through LiveKit.
     *
     * @see [AudioManager.setMode]
     */
    val audioMode: Int,
    /**
     * The audio attributes to use when playing audio through LiveKit.
     *
     * @see [AudioTrack]
     * @see [AudioFocusRequest]
     */
    val audioAttributes: AudioAttributes,
    /**
     * The audio attributes to use when playing audio through LiveKit on pre-O devices.
     *
     * @see [AudioTrack]
     * @see [AudioManager.requestAudioFocus]
     */
    val audioStreamType: Int,
) {
    /**
     * An audio type for general media playback usage (i.e. listener-only use cases).
     *
     * Audio routing is handled automatically by the system in normal media mode,
     * and bluetooth microphones may not work on some devices.
     */
    class MediaAudioType : AudioType(
        AudioManager.MODE_NORMAL,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .build(),
        AudioManager.STREAM_MUSIC,
    )

    /**
     * An audio type for calls (i.e. participating in the call or publishing local microphone).
     *
     * Audio routing can be manually controlled.
     */
    class CallAudioType : AudioType(
        AudioManager.MODE_IN_COMMUNICATION,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioManager.STREAM_VOICE_CALL,
    )

    /**
     * An audio type that takes in a user-defined [AudioAttributes] and audio stream type.
     */
    class CustomAudioType(audioMode: Int, audioAttributes: AudioAttributes, audioStreamType: Int) :
        AudioType(audioMode, audioAttributes, audioStreamType)
}
