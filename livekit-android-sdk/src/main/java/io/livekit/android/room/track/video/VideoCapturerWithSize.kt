package io.livekit.android.room.track.video

import android.hardware.camera2.CameraManager
import org.webrtc.*

/**
 * @suppress
 */
internal interface VideoCapturerWithSize : VideoCapturer {
    fun findCaptureFormat(width: Int, height: Int): Size
}

/**
 * @suppress
 */
internal class Camera1CapturerWithSize(
    private val capturer: Camera1Capturer,
    private val deviceName: String?
) : VideoCapturer by capturer, VideoCapturerWithSize {
    override fun findCaptureFormat(width: Int, height: Int): Size {
        val cameraId = Camera1Helper.getCameraId(deviceName)
        return Camera1Helper.findClosestCaptureFormat(cameraId, width, height)
    }
}

/**
 * @suppress
 */
internal class Camera2CapturerWithSize(
    private val capturer: Camera2Capturer,
    private val cameraManager: CameraManager,
    private val deviceName: String?
) : VideoCapturer by capturer, VideoCapturerWithSize {
    override fun findCaptureFormat(width: Int, height: Int): Size {
        return Camera2Helper.findClosestCaptureFormat(cameraManager, deviceName, width, height)
    }
}