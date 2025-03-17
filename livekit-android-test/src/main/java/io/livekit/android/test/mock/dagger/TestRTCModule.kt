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

package io.livekit.android.test.mock.dagger

import android.content.Context
import android.javax.sdp.SdpFactory
import dagger.Module
import dagger.Provides
import io.livekit.android.audio.AudioBufferCallbackDispatcher
import io.livekit.android.audio.AudioProcessingController
import io.livekit.android.audio.AudioRecordPrewarmer
import io.livekit.android.audio.AudioRecordSamplesDispatcher
import io.livekit.android.audio.NoAudioRecordPrewarmer
import io.livekit.android.dagger.CapabilitiesGetter
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.test.mock.MockAudioDeviceModule
import io.livekit.android.test.mock.MockAudioProcessingController
import io.livekit.android.test.mock.MockEglBase
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.MockPeerConnectionFactory
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.WebRTCInitializer
import livekit.org.webrtc.audio.AudioDeviceModule
import javax.inject.Named
import javax.inject.Singleton

@Module
object TestRTCModule {

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER)
    @Singleton
    fun localAudioBufferCallbackDispatcher(): AudioBufferCallbackDispatcher {
        return AudioBufferCallbackDispatcher()
    }

    @Provides
    @Singleton
    fun eglBase(): EglBase {
        return MockEglBase()
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext

    @Provides
    fun audioProcessingController(): AudioProcessingController {
        return MockAudioProcessingController()
    }

    @Provides
    @Singleton
    fun audioDeviceModule(): AudioDeviceModule {
        return MockAudioDeviceModule()
    }

    @Provides
    @Named(InjectionNames.LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER)
    @Singleton
    fun localAudioSamplesDispatcher(): AudioRecordSamplesDispatcher {
        return AudioRecordSamplesDispatcher()
    }

    @Provides
    fun audioPrewarmer(): AudioRecordPrewarmer {
        return NoAudioRecordPrewarmer()
    }

    @Provides
    @Singleton
    fun peerConnectionFactory(
        appContext: Context,
    ): PeerConnectionFactory {
        WebRTCInitializer.initialize(appContext)

        return MockPeerConnectionFactory()
    }

    @Provides
    @Named(InjectionNames.SENDER)
    fun senderCapabilitiesGetter(peerConnectionFactory: PeerConnectionFactory): CapabilitiesGetter {
        return { mediaType: MediaStreamTrack.MediaType ->
            peerConnectionFactory.getRtpSenderCapabilities(mediaType)
        }
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true

    @Provides
    fun sdpFactory() = SdpFactory.getInstance()
}
