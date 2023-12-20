package org.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.video.CameraCapturerWithSize
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

@ExperimentalCamera2Interop internal class CameraXCapturer(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    cameraName: String?,
    eventsHandler: CameraVideoCapturer.CameraEventsHandler?
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
            cameraControlListener
        )
    }
}

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
