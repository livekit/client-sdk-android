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

package io.livekit.android.dagger

import android.media.AudioAttributes
import dagger.Module
import dagger.Provides
import io.livekit.android.AudioType
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * @suppress
 */
@Module
object AudioHandlerModule {

    @Provides
    fun audioOutputType(
        @Named(InjectionNames.OVERRIDE_AUDIO_OUTPUT_TYPE)
        audioOutputOverride: AudioType?,
    ): AudioType {
        return audioOutputOverride ?: AudioType.CallAudioType()
    }

    @Provides
    fun audioOutputAttributes(
        audioType: AudioType,
    ): AudioAttributes {
        return audioType.audioAttributes
    }

    @Provides
    @Singleton
    fun audioHandler(
        audioSwitchHandler: Provider<AudioSwitchHandler>,
        @Named(InjectionNames.OVERRIDE_AUDIO_HANDLER)
        audioHandlerOverride: AudioHandler?,
        audioOutputType: AudioType,
    ): AudioHandler {
        return audioHandlerOverride ?: audioSwitchHandler.get().apply {
            audioMode = audioOutputType.audioMode
            audioAttributeContentType = audioOutputType.audioAttributes.contentType
            audioAttributeUsageType = audioOutputType.audioAttributes.usage
            audioStreamType = audioOutputType.audioStreamType
        }
    }
}
