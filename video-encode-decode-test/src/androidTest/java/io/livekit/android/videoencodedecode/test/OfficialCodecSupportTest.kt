package io.livekit.android.videoencodedecode.test

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory

class OfficialCodecSupportTest {
    val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(EglBase.create().eglBaseContext, true, true)

    @Before
    fun setup() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(InstrumentationRegistry.getInstrumentation().targetContext)
                .createInitializationOptions()
        )
    }

    @Test
    fun isVP8Supported() {
        Assert.assertTrue(defaultVideoEncoderFactory.supportedCodecs.any { it.name == "VP8" })
    }

    @Test
    fun isH264Supported() {
        Assert.assertTrue(defaultVideoEncoderFactory.supportedCodecs.any { it.name == "H264" })
    }
}
