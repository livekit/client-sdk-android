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

package io.livekit.android.dagger

import android.annotation.SuppressLint
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import io.livekit.android.LiveKitOverrides
import javax.inject.Named

/**
 * @suppress
 */
@SuppressLint("KotlinNullnessAnnotation")
@Module
class OverridesModule(private val overrides: LiveKitOverrides) {

    @Provides
    @Named(InjectionNames.OVERRIDE_OKHTTP)
    @Nullable
    fun okHttpClient() = overrides.okHttpClient

    @Provides
    @Named(InjectionNames.OVERRIDE_AUDIO_DEVICE_MODULE)
    @Nullable
    fun audioDeviceModule() = overrides.audioOptions?.audioDeviceModule

    @Provides
    @Named(InjectionNames.OVERRIDE_AUDIO_PROCESSOR_OPTIONS)
    @Nullable
    fun audioProcessorOptions() = overrides.audioOptions?.audioProcessorOptions

    @Provides
    @Named(InjectionNames.OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER)
    @Nullable
    fun javaAudioDeviceModuleCustomizer() = overrides.audioOptions?.javaAudioDeviceModuleCustomizer

    @Provides
    @Named(InjectionNames.OVERRIDE_VIDEO_ENCODER_FACTORY)
    @Nullable
    fun videoEncoderFactory() = overrides.videoEncoderFactory

    @Provides
    @Named(InjectionNames.OVERRIDE_VIDEO_DECODER_FACTORY)
    @Nullable
    fun videoDecoderFactory() = overrides.videoDecoderFactory

    @Provides
    @Named(InjectionNames.OVERRIDE_AUDIO_HANDLER)
    @Nullable
    fun audioHandler() = overrides.audioOptions?.audioHandler

    @Provides
    @Named(InjectionNames.OVERRIDE_AUDIO_OUTPUT_TYPE)
    fun audioOutputType() = overrides.audioOptions?.audioOutputType

    @Provides
    @Named(InjectionNames.OVERRIDE_PEER_CONNECTION_FACTORY_OPTIONS)
    fun peerConnectionFactoryOptions() = overrides.peerConnectionFactoryOptions

    @Provides
    @Named(InjectionNames.OVERRIDE_DISABLE_COMMUNICATION_WORKAROUND)
    fun disableCommunicationWorkAround() = overrides.audioOptions?.disableCommunicationModeWorkaround ?: false

    @Provides
    @Named(InjectionNames.OVERRIDE_EGL_BASE)
    @Nullable
    fun eglBase() = overrides.eglBase
}
