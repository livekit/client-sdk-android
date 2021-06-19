package io.livekit.android.room.track

import android.content.Context
import com.github.ajalt.timberkt.Timber
import org.webrtc.*
import java.util.*

/**
 * A representation of a local video track (generally input coming from camera or screen).
 *
 * [startCapture] should be called before use.
 */
class LocalVideoTrack(
    private val capturer: VideoCapturer,
    private val source: VideoSource,
    name: String,
    private val options: LocalVideoTrackOptions,
    rtcTrack: org.webrtc.VideoTrack
) : VideoTrack(name, rtcTrack) {
    val dimensions: Dimensions

    init {
        dimensions = Dimensions(options.captureParams.width, options.captureParams.height)
    }

    fun startCapture() {
        capturer.startCapture(options.captureParams.width, options.captureParams.height, options.captureParams.maxFps)
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    companion object {
        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            val capturer = createVideoCapturer(context, options.position) ?: TODO()
            capturer.initialize(
                SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                source.capturerObserver
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return LocalVideoTrack(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = track,
            )
        }

        private fun createVideoCapturer(context: Context, position: CameraPosition): VideoCapturer? {
            val videoCapturer: VideoCapturer? = if (Camera2Enumerator.isSupported(context)) {
                createCameraCapturer(Camera2Enumerator(context), position)
            } else {
                createCameraCapturer(Camera1Enumerator(true), position)
            }
            if (videoCapturer == null) {
                Timber.d { "Failed to open camera" }
                return null
            }
            return videoCapturer
        }

        private fun createCameraCapturer(enumerator: CameraEnumerator, position: CameraPosition): VideoCapturer? {
            val deviceNames = enumerator.deviceNames

            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName) && position == CameraPosition.FRONT) {
                    Timber.v { "Creating front facing camera capturer." }
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        return videoCapturer
                    }
                } else if (enumerator.isBackFacing(deviceName) && position == CameraPosition.BACK) {
                    Timber.v { "Creating back facing camera capturer." }
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        return videoCapturer
                    }
                }
            }
            return null
        }
    }
}