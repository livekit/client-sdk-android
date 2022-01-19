package io.livekit.android.mock.dagger

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import io.livekit.android.dagger.JsonFormatModule
import io.livekit.android.dagger.LiveKitComponent
import io.livekit.android.mock.MockWebsocketFactory
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        TestCoroutinesModule::class,
        TestRTCModule::class,
        TestWebModule::class,
        JsonFormatModule::class,
    ]
)
internal interface TestLiveKitComponent : LiveKitComponent {

    fun websocketFactory(): MockWebsocketFactory

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance appContext: Context, coroutinesModule: TestCoroutinesModule = TestCoroutinesModule()): TestLiveKitComponent
    }
}