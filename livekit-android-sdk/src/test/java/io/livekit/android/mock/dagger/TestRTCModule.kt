package io.livekit.android.mock.dagger

import android.content.Context
import dagger.Module
import dagger.Provides
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.mock.MockEglBase
import org.webrtc.*
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
    fun peerConnectionFactory(
        appContext: Context
    ): PeerConnectionFactory {
        WebRTCInitializer.initialize(appContext)

        return MockPeerConnectionFactory()
    }

    @Provides
    @Named(InjectionNames.OPTIONS_VIDEO_HW_ACCEL)
    fun videoHwAccel() = true
}