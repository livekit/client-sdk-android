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

package livekit.org.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

class CameraXHelper {
    companion object {

        @ExperimentalCamera2Interop
        fun getCameraProvider(
            lifecycleOwner: LifecycleOwner,
            controlListener: CameraXSession.CameraControlListener?,
        ) = object : CameraCapturerUtils.CameraProvider {

            private var enumerator: CameraXEnumerator? = null

            override val cameraVersion = 3

            override fun provideEnumerator(context: Context): CameraXEnumerator =
                enumerator ?: CameraXEnumerator(context, lifecycleOwner).also {
                    enumerator = it
                }

            override fun provideCapturer(
                context: Context,
                options: LocalVideoTrackOptions,
                eventsHandler: CameraEventsDispatchHandler,
            ): VideoCapturer {
                val enumerator = provideEnumerator(context)
                val targetDeviceName = enumerator.findCamera(options.deviceId, options.position)
                val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, eventsHandler) as CameraXCapturer
                controlListener?.let {
                    targetVideoCapturer.cameraControlListener = it
                }
                return CameraXCapturerWithSize(
                    targetVideoCapturer,
                    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                    targetDeviceName,
                    eventsHandler,
                )
            }

            override fun isSupported(context: Context) = Camera2Enumerator.isSupported(context)
        }

        private fun getSupportedFormats(
            cameraManager: CameraManager,
            cameraId: String?,
        ): List<CameraEnumerationAndroid.CaptureFormat>? =
            Camera2Enumerator.getSupportedFormats(cameraManager, cameraId)

        fun findClosestCaptureFormat(
            cameraManager: CameraManager,
            cameraId: String?,
            width: Int,
            height: Int,
        ): Size {
            val sizes = getSupportedFormats(cameraManager, cameraId)
                ?.map { Size(it.width, it.height) }
                ?: emptyList()
            return CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height)
        }
    }
}
