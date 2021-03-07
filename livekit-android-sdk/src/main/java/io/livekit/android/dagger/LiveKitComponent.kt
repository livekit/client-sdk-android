package io.livekit.android.dagger

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import io.livekit.android.room.Room
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        RTCModule::class,
        WebModule::class,
        JsonFormatModule::class,
    ]
)
interface LiveKitComponent {

    fun roomFactory(): Room.Factory

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance appContext: Context): LiveKitComponent
    }
}