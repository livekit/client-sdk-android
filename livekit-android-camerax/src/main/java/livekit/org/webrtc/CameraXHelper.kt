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

package livekit.org.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.UseCase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

class CameraXHelper {
    companion object {
        /**
         * Creates a CameraProvider that uses CameraX for its sessions.
         *
         * For use with [CameraCapturerUtils.registerCameraProvider].
         * Remember to unregister the provider when outside the lifecycle
         * of [lifecycleOwner].
         *
         * @param lifecycleOwner The lifecycleOwner which controls the lifecycle transitions of the use cases.
         * @param useCases The use cases to bind to a lifecycle.
         */
        @JvmOverloads
        @ExperimentalCamera2Interop
        fun createCameraProvider(
            lifecycleOwner: LifecycleOwner,
            useCases: Array<out UseCase> = emptyArray(),
        ) = object : CameraCapturerUtils.CameraProvider {

            private var enumerator: CameraXEnumerator? = null

            override val cameraVersion = 3

            override fun provideEnumerator(context: Context): CameraXEnumerator =
                enumerator ?: CameraXEnumerator(context, lifecycleOwner, useCases).also {
                    enumerator = it
                }

            override fun provideCapturer(
                context: Context,
                options: LocalVideoTrackOptions,
                eventsHandler: CameraEventsDispatchHandler,
            ): VideoCapturer {
                val enumerator = provideEnumerator(context)
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val deviceId = options.deviceId
                var targetDeviceName: String? = null
                if (deviceId != null) {
                    targetDeviceName = findCameraById(cameraManager, deviceId)
                }
                if (targetDeviceName == null) {
                    // Fallback to enumerator.findCamera which can't find camera by physical id but it will choose the closest one.
                    targetDeviceName = enumerator.findCamera(deviceId, options.position)
                }
                val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, eventsHandler) as CameraXCapturer

                return CameraXCapturerWithSize(
                    targetVideoCapturer,
                    cameraManager,
                    targetDeviceName,
                    eventsHandler,
                )
            }

            override fun isSupported(context: Context): Boolean {
                return Camera2Enumerator.isSupported(context) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
            }

            private fun findCameraById(cameraManager: CameraManager, deviceId: String): String? {
                for (id in cameraManager.cameraIdList) {
                    if (id == deviceId) return id // This means the provided id is logical id.

                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val ids = characteristics.physicalCameraIds
                        if (ids.contains(deviceId)) {
                            // This means the provided id is physical id.
                            enumerator?.physicalCameraId = deviceId
                            return id // This is its logical id.
                        }
                    }
                }
                return null
            }
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
