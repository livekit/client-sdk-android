package io.livekit.android.mock

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

class MockVideoCapturer : VideoCapturer {
    override fun initialize(p0: SurfaceTextureHelper?, p1: Context?, p2: CapturerObserver?) {
    }

    override fun startCapture(p0: Int, p1: Int, p2: Int) {
    }

    override fun stopCapture() {
    }

    override fun changeCaptureFormat(p0: Int, p1: Int, p2: Int) {
    }

    override fun dispose() {
    }

    override fun isScreencast(): Boolean {
        return false
    }
}