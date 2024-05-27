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

/**
 * A helper to access package-protected methods used in [Camera2Session]
 *
 * Note: cameraId as used in the Camera1XXX classes refers to the index within the list of cameras.
 * @suppress
 */
internal class Camera1Helper {
    companion object {
        fun getCameraId(deviceName: String?) = Camera1Enumerator.getCameraIndex(deviceName)

        fun getSupportedFormats(cameraId: Int): List<CameraEnumerationAndroid.CaptureFormat> =
            Camera1Enumerator.getSupportedFormats(cameraId)

        fun findClosestCaptureFormat(
            cameraId: Int,
            width: Int,
            height: Int,
        ): Size {
            return CameraEnumerationAndroid.getClosestSupportedSize(
                getSupportedFormats(cameraId)
                    .map { Size(it.width, it.height) },
                width,
                height,
            )
        }
    }
}
