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
    rtcTrack: org.webrtc.VideoTrack
) : VideoTrack(name, rtcTrack) {
    fun startCapture() {
        capturer.startCapture(400, 400, 30)
    }

    companion object {
        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            isScreencast: Boolean,
            name: String,
            rootEglBase: EglBase,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(isScreencast)
            val capturer = createVideoCapturer(context) ?: TODO()
            capturer.initialize(
                SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext),
                context,
                source.capturerObserver
            )
            val track = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            return LocalVideoTrack(
                capturer = capturer,
                source = source,
                name = name,
                rtcTrack = track,
            )
        }


        private fun createVideoCapturer(context: Context): VideoCapturer? {
            val videoCapturer: VideoCapturer? = if (Camera2Enumerator.isSupported(context)) {
                createCameraCapturer(Camera2Enumerator(context))
            } else {
                createCameraCapturer(Camera1Enumerator(true))
            }
            if (videoCapturer == null) {
                Timber.d { "Failed to open camera" }
                return null
            }
            return videoCapturer
        }

        private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
            val deviceNames = enumerator.deviceNames

            // First, try to find front facing camera
            for (deviceName in deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    Timber.v { "Creating front facing camera capturer." }
                    val videoCapturer = enumerator.createCapturer(deviceName, null)
                    if (videoCapturer != null) {
                        return videoCapturer
                    }
                }
            }

            // Front facing camera not found, try something else
            for (deviceName in deviceNames) {
                if (!enumerator.isFrontFacing(deviceName)) {
                    Timber.v { "Creating other camera capturer." }
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