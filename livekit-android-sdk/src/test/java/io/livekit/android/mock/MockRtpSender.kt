package io.livekit.android.mock

import org.mockito.Mockito
import org.webrtc.RtpSender

object MockRtpSender {
    fun create(): RtpSender {
        return Mockito.mock(RtpSender::class.java)
    }
}