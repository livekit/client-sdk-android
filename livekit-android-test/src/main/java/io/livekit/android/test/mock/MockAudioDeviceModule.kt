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

package io.livekit.android.test.mock

import livekit.org.webrtc.audio.AudioDeviceModule

class MockAudioDeviceModule : AudioDeviceModule {
    override fun getNativeAudioDeviceModulePointer(): Long {
        return 1
    }

    override fun release() {
    }

    override fun setSpeakerMute(muted: Boolean) {
    }

    override fun setMicrophoneMute(muted: Boolean) {
    }
}
