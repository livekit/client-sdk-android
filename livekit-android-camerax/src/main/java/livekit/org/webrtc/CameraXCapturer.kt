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

package livekit.org.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.video.CameraCapturerWithSize
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

@ExperimentalCamera2Interop
internal class CameraXCapturer(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    cameraName: String?,
    eventsHandler: CameraVideoCapturer.CameraEventsHandler?,
) : CameraCapturer(cameraName, eventsHandler, Camera2Enumerator(context)) {

    var cameraControlListener: CameraXSession.CameraControlListener? = null

    override fun createCameraSession(
        createSessionCallback: CameraSession.CreateSessionCallback,
        events: CameraSession.Events,
        applicationContext: Context,
        surfaceTextureHelper: SurfaceTextureHelper,
        cameraName: String,
        width: Int,
        height: Int,
        framerate: Int,
    ) {
        CameraXSession(
            createSessionCallback,
            events,
            applicationContext,
            lifecycleOwner,
            surfaceTextureHelper,
            cameraName,
            width,
            height,
            framerate,
            cameraControlListener,
        )
    }
}

@ExperimentalCamera2Interop
internal class CameraXCapturerWithSize(
    private val capturer: CameraXCapturer,
    private val cameraManager: CameraManager,
    private val deviceName: String?,
    cameraEventsDispatchHandler: CameraEventsDispatchHandler,
) : CameraCapturerWithSize(cameraEventsDispatchHandler), CameraVideoCapturer by capturer {
    override fun findCaptureFormat(width: Int, height: Int): Size {
        return CameraXHelper.findClosestCaptureFormat(cameraManager, deviceName, width, height)
    }
}
