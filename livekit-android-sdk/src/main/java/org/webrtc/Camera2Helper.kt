package org.webrtc

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
            height: Int
        ): Size {
            val sizes = getSupportedFormats(cameraManager, cameraId)
                ?.map { Size(it.width, it.height) }
                ?: emptyList()
            return CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height)
        }
    }
}