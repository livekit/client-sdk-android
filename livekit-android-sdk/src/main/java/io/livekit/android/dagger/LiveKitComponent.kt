package io.livekit.android.dagger

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        CoroutinesModule::class,
        RTCModule::class,
        WebModule::class,
        JsonFormatModule::class,
        OverridesModule::class,
        AudioHandlerModule::class,
    ]
)
internal interface LiveKitComponent {

    fun roomFactory(): Room.Factory

    fun peerConnectionFactory(): PeerConnectionFactory

    fun eglBase(): EglBase

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance appContext: Context,
            overridesModule: OverridesModule
        ): LiveKitComponent
    }
}

internal fun LiveKitComponent.Factory.create(
    context: Context,
    overrides: LiveKitOverrides,
): LiveKitComponent {
    return create(
        appContext = context,
        overridesModule = OverridesModule(overrides)
    )
}

