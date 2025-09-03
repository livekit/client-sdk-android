package livekit.org.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.UseCase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

@ExperimentalCamera2Interop
class CameraXProvider(
    val lifecycleOwner: LifecycleOwner,
    val useCases: Array<out UseCase> = emptyArray(),
    override val cameraVersion: Int = 3,
) : CameraCapturerUtils.CameraProvider {

    var enumerator: CameraXEnumerator? = null
        private set

    override fun provideEnumerator(context: Context): CameraXEnumerator = enumerator ?: CameraXEnumerator(context, lifecycleOwner, useCases).also {
        enumerator = it
    }

    override fun provideCapturer(
        context: Context,
        options: LocalVideoTrackOptions,
        eventsHandler: CameraEventsDispatchHandler,
    ): VideoCapturer {
        val enumerator = provideEnumerator(context)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val targetDevice = enumerator.findCamera(options.deviceId, options.position)
        val targetDeviceId = targetDevice?.deviceId

        val targetVideoCapturer = enumerator.createCapturer(targetDeviceId, eventsHandler) as CameraXCapturer

        return CameraXCapturerWithSize(
            targetVideoCapturer,
            cameraManager,
            targetDeviceId,
            eventsHandler,
        )
    }

    override fun isSupported(context: Context): Boolean {
        return Camera2Enumerator.isSupported(context) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
    }
}
