package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.video.Camera1CapturerWithSize
import io.livekit.android.room.track.video.Camera2CapturerWithSize
import io.livekit.android.room.track.video.CapturerObserverDelegate
import io.livekit.android.room.track.video.VideoCapturerWithSize
import io.livekit.android.util.LKLog
import org.webrtc.*
import java.util.*


/**
 * A representation of a local video track (generally input coming from camera or screen).
 *
 * [startCapture] should be called before use.
 */
open class LocalVideoTrack
@AssistedInject
constructor(
    @Assisted private var capturer: VideoCapturer,
    @Assisted private var source: VideoSource,
    @Assisted name: String,
    @Assisted var options: LocalVideoTrackOptions,
    @Assisted rtcTrack: org.webrtc.VideoTrack,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val defaultsManager: DefaultsManager,
    private val trackFactory: Factory,
) : VideoTrack(name, rtcTrack) {

    override var rtcTrack: org.webrtc.VideoTrack = rtcTrack
        internal set

    val dimensions: Dimensions
        get() {
            (capturer as? VideoCapturerWithSize)?.let { capturerWithSize ->
                val size = capturerWithSize.findCaptureFormat(
                    options.captureParams.width,
                    options.captureParams.height
                )
                return Dimensions(size.width, size.height)
            }
            return Dimensions(options.captureParams.width, options.captureParams.height)
        }

    internal var transceiver: RtpTransceiver? = null
    private val sender: RtpSender?
        get() = transceiver?.sender

    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps
        )
    }

    open fun stopCapture() {
        capturer.stopCapture()
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

    /**
     * Switch to a different camera. Only works if this track is backed by a camera capturer.
     *
     * If neither deviceId or position is provided, or the specified camera cannot be found,
     * this will switch to the next camera, if one is available.
     */
    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {

        val cameraCapturer = capturer as? CameraVideoCapturer ?: run {
            LKLog.w { "Attempting to switch camera on a non-camera video track!" }
            return
        }

        var targetDeviceId: String? = null
        val enumerator = createCameraEnumerator(context)
        if (deviceId != null || position != null) {
            targetDeviceId = enumerator.findCamera(deviceId, position, fallback = false)
        }

        if (targetDeviceId == null) {
            val deviceNames = enumerator.deviceNames
            if (deviceNames.size < 2) {
                LKLog.w { "No available cameras to switch to!" }
                return
            }
            val currentIndex = deviceNames.indexOf(options.deviceId)
            targetDeviceId = deviceNames[(currentIndex + 1) % deviceNames.size]
        }

        val cameraSwitchHandler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                val newOptions = options.copy(
                    deviceId = targetDeviceId,
                    position = enumerator.getCameraPosition(targetDeviceId)
                )
                options = newOptions
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                LKLog.w { "switching camera failed: $errorDescription" }
            }

        }
        if (targetDeviceId == null) {
            LKLog.w { "No target camera found!" }
            return
        } else {
            cameraCapturer.switchCamera(cameraSwitchHandler, targetDeviceId)
        }
    }

    /**
     * Restart a track with new options.
     */
    fun restartTrack(options: LocalVideoTrackOptions = defaultsManager.videoTrackCaptureDefaults.copy()) {
        val newTrack = createTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            trackFactory
        )

        val oldCapturer = capturer
        val oldSource = source
        val oldRtcTrack = rtcTrack

        oldCapturer.stopCapture()
        oldCapturer.dispose()
        oldSource.dispose()

        // sender owns rtcTrack, so it'll take care of disposing it.
        oldRtcTrack.setEnabled(false)

        // migrate video sinks to the new track
        for (sink in sinks) {
            oldRtcTrack.removeSink(sink)
            newTrack.addRenderer(sink)
        }

        capturer = newTrack.capturer
        source = newTrack.source
        rtcTrack = newTrack.rtcTrack
        this.options = options
        startCapture()
        sender?.setTrack(newTrack.rtcTrack, true)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: org.webrtc.VideoTrack,
        ): LocalVideoTrack
    }

    companion object {

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            capturer: VideoCapturer,
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            rootEglBase: EglBase,
            trackFactory: Factory
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(false)
            val capturerObservers = listOfNotNull(source.capturerObserver, options.capturerObserverFactory?.invoke())

            capturer.initialize(
                SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                CapturerObserverDelegate(capturerObservers)
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = track
            )
        }

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory
        ): LocalVideoTrack {

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a camera video track.")
            }

            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            val capturerObservers = listOfNotNull(source.capturerObserver, options.capturerObserverFactory?.invoke())
            val (capturer, newOptions) = createVideoCapturer(context, options) ?: TODO()
            capturer.initialize(
                SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                CapturerObserverDelegate(capturerObservers)
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return trackFactory.create(
                capturer = capturer,
                source = source,
                options = newOptions,
                name = name,
                rtcTrack = track
            )
        }

        private fun createCameraEnumerator(context: Context): CameraEnumerator {
            return if (Camera2Enumerator.isSupported(context)) {
                Camera2Enumerator(context)
            } else {
                Camera1Enumerator(true)
            }
        }

        private fun createVideoCapturer(
            context: Context,
            options: LocalVideoTrackOptions
        ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
            val cameraEnumerator = createCameraEnumerator(context)
            val pair = createCameraCapturer(context, cameraEnumerator, options)

            if (pair == null) {
                LKLog.d { "Failed to open camera" }
                return null
            }
            return pair
        }

        private fun createCameraCapturer(
            context: Context,
            enumerator: CameraEnumerator,
            options: LocalVideoTrackOptions
        ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
            val targetDeviceName = enumerator.findCamera(options.deviceId, options.position) ?: return null
            val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, null)

            // back fill any missing information
            val newOptions = options.copy(
                deviceId = targetDeviceName,
                position = enumerator.getCameraPosition(targetDeviceName)
            )
            if (targetVideoCapturer is Camera1Capturer) {
                // Cache supported capture formats ahead of time to avoid future camera locks.
                Camera1Helper.getSupportedFormats(Camera1Helper.getCameraId(newOptions.deviceId))
                return Pair(
                    Camera1CapturerWithSize(targetVideoCapturer, targetDeviceName),
                    newOptions
                )
            }

            if (targetVideoCapturer is Camera2Capturer) {
                return Pair(
                    Camera2CapturerWithSize(
                        targetVideoCapturer,
                        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                        targetDeviceName
                    ),
                    newOptions
                )
            }

            LKLog.w { "unknown CameraCapturer class: ${targetVideoCapturer.javaClass.canonicalName}. Reported dimensions may be inaccurate." }
            if (targetVideoCapturer != null) {
                return Pair(
                    targetVideoCapturer,
                    newOptions
                )
            }

            return null
        }

        private fun CameraEnumerator.findCamera(
            deviceId: String?,
            position: CameraPosition?,
            fallback: Boolean = true
        ): String? {
            var targetDeviceName: String? = null
            // Prioritize search by deviceId first
            if (deviceId != null) {
                targetDeviceName = findCamera { deviceName -> deviceName == deviceId }
            }

            // Search by camera position
            if (targetDeviceName == null && position != null) {
                targetDeviceName = findCamera { deviceName ->
                    getCameraPosition(deviceName) == position
                }
            }

            // Fall back by choosing first available camera.
            if (targetDeviceName == null && fallback) {
                targetDeviceName = findCamera { true }
            }

            if (targetDeviceName == null) {
                return null
            }

            return targetDeviceName
        }

        private fun CameraEnumerator.findCamera(predicate: (deviceName: String) -> Boolean): String? {
            for (deviceName in deviceNames) {
                if (predicate(deviceName)) {
                    val videoCapturer = createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        return deviceName
                    }
                }
            }
            return null
        }

        private fun CameraEnumerator.getCameraPosition(deviceName: String?): CameraPosition? {
            if (deviceName == null) {
                return null
            }
            if (isBackFacing(deviceName)) {
                return CameraPosition.BACK
            } else if (isFrontFacing(deviceName)) {
                return CameraPosition.FRONT
            }
            return null
        }
    }

}
