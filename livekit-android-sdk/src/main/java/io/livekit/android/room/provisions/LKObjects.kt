/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.room.provisions

import livekit.org.webrtc.EglBase
import livekit.org.webrtc.audio.AudioDeviceModule
import javax.inject.Inject
import javax.inject.Provider

/**
 * Provides access to objects used internally.
 */
// Note, to avoid accidentally instantiating an unneeded object,
// only store Providers here.
//
// Additionally, the provided objects should only be singletons.
// Otherwise the created objects may not be the one used internally.
class LKObjects
@Inject
constructor(
    private val eglBaseProvider: Provider<EglBase>,
    private val audioDeviceModuleProvider: Provider<AudioDeviceModule>,
) {
    val eglBase: EglBase
        get() = eglBaseProvider.get()

    val audioDeviceModule: AudioDeviceModule
        get() = audioDeviceModuleProvider.get()
}
