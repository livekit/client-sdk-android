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

package io.livekit.android.test.mock

import android.content.Context
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer

class MockVideoCapturer : VideoCapturer {
    override fun initialize(p0: SurfaceTextureHelper?, p1: Context?, p2: CapturerObserver?) {
    }

    override fun startCapture(p0: Int, p1: Int, p2: Int) {
    }

    override fun stopCapture() {
    }

    override fun changeCaptureFormat(p0: Int, p1: Int, p2: Int) {
    }

    override fun dispose() {
    }

    override fun isScreencast(): Boolean {
        return false
    }
}
