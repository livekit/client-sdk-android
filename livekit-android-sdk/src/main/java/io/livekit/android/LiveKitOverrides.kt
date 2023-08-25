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

import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.NoAudioHandler
import okhttp3.OkHttpClient
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Overrides to replace LiveKit internally used component with custom implementations.
 */
data class LiveKitOverrides(
    /**
     * Override the [OkHttpClient] used by the library.
     */
    val okHttpClient: OkHttpClient? = null,

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

    /**
     * Override the [VideoEncoderFactory] used by the library.
     */
    val videoEncoderFactory: VideoEncoderFactory? = null,

    /**
     * Override the [VideoDecoderFactory] used by the library.
     */
    val videoDecoderFactory: VideoDecoderFactory? = null,

    /**
     * Override the default [AudioHandler].
     *
     * Use [NoAudioHandler] to turn off automatic audio handling.
     */

    val audioHandler: AudioHandler? = null
)