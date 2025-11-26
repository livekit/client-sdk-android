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

package io.livekit.android.room.track.video

import livekit.org.webrtc.CameraVideoCapturer.CameraEventsHandler
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Dispatches CameraEventsHandler callbacks to registered handlers.
 */
class CameraEventsDispatchHandler : CameraEventsHandler {
    private val handlers = CopyOnWriteArraySet<CameraEventsHandler>()

    fun registerHandler(handler: CameraEventsHandler) {
        handlers.add(handler)
    }

    fun unregisterHandler(handler: CameraEventsHandler) {
        handlers.remove(handler)
    }

    override fun onCameraError(errorDescription: String) {
        for (handler in handlers) {
            handler.onCameraError(errorDescription)
        }
    }

    override fun onCameraDisconnected() {
        for (handler in handlers) {
            handler.onCameraDisconnected()
        }
    }

    override fun onCameraFreezed(errorDescription: String) {
        for (handler in handlers) {
            handler.onCameraFreezed(errorDescription)
        }
    }

    override fun onCameraOpening(cameraName: String) {
        for (handler in handlers) {
            handler.onCameraOpening(cameraName)
        }
    }

    override fun onFirstFrameAvailable() {
        for (handler in handlers) {
            handler.onFirstFrameAvailable()
        }
    }

    override fun onCameraClosed() {
        for (handler in handlers) {
            handler.onCameraClosed()
        }
    }
}
