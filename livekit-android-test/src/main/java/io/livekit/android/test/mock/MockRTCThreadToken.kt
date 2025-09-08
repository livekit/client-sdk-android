package io.livekit.android.test.mock

import io.livekit.android.webrtc.peerconnection.RTCThreadToken

class MockRTCThreadToken : RTCThreadToken {
    override val isDisposed: Boolean
        get() = true
}
