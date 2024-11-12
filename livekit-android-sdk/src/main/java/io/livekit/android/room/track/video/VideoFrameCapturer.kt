package io.livekit.android.room.track.video

import android.content.Context
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame

/**
 * A [VideoCapturer] that can be manually driven by passing in [VideoFrame] to [pushVideoFrame].
 *
 * Once [startCapture] is called, call [pushVideoFrame] to publish video frames.
 */
open class VideoFrameCapturer : VideoCapturer {

    var capturerObserver: CapturerObserver? = null

    // This is automatically called when creating the LocalVideoTrack with the capturer.
    override fun initialize(helper: SurfaceTextureHelper, context: Context?, capturerObserver: CapturerObserver) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        capturerObserver?.onCapturerStarted(true)
    }

    override fun stopCapture() {
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
    }

    override fun dispose() {
    }

    override fun isScreencast(): Boolean = false

    fun pushVideoFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }
}
