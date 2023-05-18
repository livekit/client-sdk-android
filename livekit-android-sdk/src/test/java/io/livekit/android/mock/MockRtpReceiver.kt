package io.livekit.android.mock

import org.mockito.Mockito
import org.webrtc.RtpReceiver

object MockRtpReceiver {
    fun create(): RtpReceiver {
        return Mockito.mock(RtpReceiver::class.java)
    }
}