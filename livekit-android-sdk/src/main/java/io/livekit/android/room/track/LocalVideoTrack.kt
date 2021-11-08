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

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

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
            val (capturer, newOptions) = createVideoCapturer(context, options) ?: TODO()
            capturer.initialize(
                SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                source.capturerObserver
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

        private fun createVideoCapturer(
            context: Context,
            options: LocalVideoTrackOptions
        ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
            val pair = if (Camera2Enumerator.isSupported(context)) {
                createCameraCapturer(context, Camera2Enumerator(context), options)
            } else {
                createCameraCapturer(context, Camera1Enumerator(true), options)
            }

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
        ): Pair<VideoCapturerWithSize, LocalVideoTrackOptions>? {
            val deviceNames = enumerator.deviceNames
            var targetDeviceName: String? = null
            var targetVideoCapturer: VideoCapturer? = null
            for (deviceName in deviceNames) {
                if ((options.deviceId != null && deviceName == options.deviceId)
                    || (enumerator.isFrontFacing(deviceName) && options.position == CameraPosition.FRONT)
                ) {
                    LKLog.v { "Creating front facing camera capturer." }
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        targetDeviceName = deviceName
                        targetVideoCapturer = videoCapturer
                        break
                    }
                } else if ((options.deviceId != null && deviceName == options.deviceId)
                    || (enumerator.isBackFacing(deviceName) && options.position == CameraPosition.BACK)
                ) {
                    LKLog.v { "Creating back facing camera capturer." }
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        targetDeviceName = deviceName
                        targetVideoCapturer = videoCapturer
                        break
                    }
                }
            }

            // back fill any missing information
            val newOptions = options.copy(
                deviceId = targetDeviceName,
                position = enumerator.getCameraPosition(targetDeviceName!!)
            )
            if (targetVideoCapturer is Camera1Capturer) {
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

            return null
        }

        fun CameraEnumerator.getCameraPosition(deviceName: String): CameraPosition? {
            if (isBackFacing(deviceName)) {
                return CameraPosition.BACK
            } else if (isFrontFacing(deviceName)) {
                return CameraPosition.FRONT
            }
            return null
        }
    }

}
