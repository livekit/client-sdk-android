package org.webrtc

/**
 * A helper to access package-protected methods used in [Camera2Session]
 * @suppress
 */
internal class Camera1Helper {
    companion object {
        fun getCameraId(deviceName: String?) = Camera1Enumerator.getCameraIndex(deviceName)

        fun findClosestCaptureFormat(
            cameraId: Int,
            width: Int,
            height: Int
        ): Size {
            return CameraEnumerationAndroid.getClosestSupportedSize(
                Camera1Enumerator.getSupportedFormats(cameraId)
                    .map { Size(it.width, it.height) },
                width,
                height
            )
        }
    }
}