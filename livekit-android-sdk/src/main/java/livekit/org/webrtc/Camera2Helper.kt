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

import android.hardware.camera2.CameraManager

/**
 * A helper to access package-protected methods used in [Camera2Session]
 *
 * Note: cameraId as used in the Camera2XXX classes refers to the id returned
 * by [CameraManager.getCameraIdList].
 * @suppress
 */
internal class Camera2Helper {
    companion object {

        fun getSupportedFormats(
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
