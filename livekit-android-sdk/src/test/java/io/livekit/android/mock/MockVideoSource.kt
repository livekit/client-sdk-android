package io.livekit.android.mock

import org.webrtc.VideoSource

class MockVideoSource(nativeSource: Long = 100) : VideoSource(nativeSource) {
}