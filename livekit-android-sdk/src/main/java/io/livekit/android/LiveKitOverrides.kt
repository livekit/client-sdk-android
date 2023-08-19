/*
 * Copyright 2023 LiveKit, Inc.
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
import android.media.AudioManager
import io.livekit.android.audio.AudioFocusHandler
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.audio.NoAudioHandler
import okhttp3.OkHttpClient
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

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

    val audioOptions: AudioOptions? = null,
)


class AudioOptions(
    /**
     * Override the default output [AudioType].
     *
     * This affects the audio routing and how the audio is handled. Default is [AudioType.CallAudioType].
     *
     * Note: if [audioHandler] is also passed, the values from [audioOutputType] will not be reflected in it,
     * and must be set manually.
     */
    val audioOutputType: AudioType? = null,
    /**
     * Override the default [AudioHandler].
     *
     * Use [NoAudioHandler] to turn off automatic audio handling.
     */
    val audioHandler: AudioHandler? = null,

    /**
     * Override the default [AudioDeviceModule].
     */
    val audioDeviceModule: AudioDeviceModule? = null,

    /**
     * Called after default setup to allow for customizations on the [JavaAudioDeviceModule].
     *
     * Not used if [audioDeviceModule] is provided.
     */
    val javaAudioDeviceModuleCustomizer: ((builder: JavaAudioDeviceModule.Builder) -> Unit)? = null,
)

sealed class AudioType(val audioMode: Int, val audioAttributes: AudioAttributes, val audioStreamType: Int) {
    /**
     * An audio type for general media playback usage (i.e. listener-only use cases).
     *
     * Audio routing is handled automatically by the system in normal media mode,
     * and bluetooth microphones may not work on some devices.
     *
     * The default [AudioHandler] for this type is [AudioFocusHandler].
     */
    class MediaAudioType : AudioType(
        AudioManager.MODE_NORMAL,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioManager.STREAM_MUSIC
    )

    /**
     * An audio type for calls (i.e. participanting in the call or publishing local microphone).
     *
     * Audio routing can be manually controlled.
     *
     * The default [AudioHandler] for this type is [AudioSwitchHandler].
     */
    class CallAudioType : AudioType(
        AudioManager.MODE_IN_COMMUNICATION,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        AudioManager.STREAM_VOICE_CALL
    )

    /**
     * An audio type that takes in a user-defined [AudioAttributes] and audio stream type.
     *
     * The default [AudioHandler] for this type is [AudioFocusHandler].
     */
    class CustomAudioType(audioMode: Int, audioAttributes: AudioAttributes, audioStreamType: Int) :
        AudioType(audioMode, audioAttributes, audioStreamType)
}