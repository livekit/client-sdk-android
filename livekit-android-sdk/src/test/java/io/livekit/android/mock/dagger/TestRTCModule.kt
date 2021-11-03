package io.livekit.android.mock.dagger

import dagger.Module
import dagger.Provides
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.mock.MockEglBase
import org.webrtc.EglBase
import org.webrtc.MockPeerConnectionFactory
import org.webrtc.PeerConnectionFactory
import javax.inject.Named
import javax.inject.Singleton


@Module
object TestRTCModule {

    @Provides
    @Singleton
    fun eglBase(): EglBase {
        return MockEglBase()
    }

    @Provides
    fun eglContext(eglBase: EglBase): EglBase.Context = eglBase.eglBaseContext


    @Provides
    @Singleton
    fun peerConnectionFactory(): PeerConnectionFactory {
        return MockPeerConnectionFactory()
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true
}