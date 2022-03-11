package io.livekit.android.dagger

import android.content.Context
import androidx.annotation.Nullable
import dagger.BindsInstance
import dagger.Component
import io.livekit.android.room.Room
import okhttp3.OkHttpClient
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import javax.inject.Named
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        CoroutinesModule::class,
        RTCModule::class,
        WebModule::class,
        JsonFormatModule::class,
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

            @BindsInstance
            @Named(InjectionNames.OVERRIDE_OKHTTP)
            @Nullable
            okHttpClientOverride: OkHttpClient?
        ): LiveKitComponent
    }
}

internal fun LiveKitComponent.Factory.create(
    context: Context,
    overrides: LiveKitOverrides,
): LiveKitComponent {
    return create(
        appContext = context,
        okHttpClientOverride = overrides.okHttpClient
    )
}

/**
 * Overrides to replace LiveKit internally used component with custom implementations.
 */
data class LiveKitOverrides(
    val okHttpClient: OkHttpClient? = null
)