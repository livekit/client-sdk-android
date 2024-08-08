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

package io.livekit.android.memory

import livekit.org.webrtc.SurfaceTextureHelper
import java.io.Closeable

internal class SurfaceTextureHelperCloser(private val surfaceTextureHelper: SurfaceTextureHelper?) : Closeable {
    private var isClosed = false
    override fun close() {
        if (!isClosed) {
            isClosed = true
            surfaceTextureHelper?.stopListening()
            surfaceTextureHelper?.dispose()
        }
    }
}
