/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.test.mock.dagger

import android.content.Context
import dagger.BindsInstance
import dagger.Component
import io.livekit.android.dagger.JsonFormatModule
import io.livekit.android.dagger.LiveKitComponent
import io.livekit.android.dagger.MemoryModule
import io.livekit.android.room.RTCEngine
import io.livekit.android.test.mock.MockNetworkCallbackRegistry
import io.livekit.android.test.mock.MockWebSocketFactory
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        TestCoroutinesModule::class,
        TestRTCModule::class,
        TestWebModule::class,
        TestAudioHandlerModule::class,
        JsonFormatModule::class,
        MemoryModule::class,
    ],
)
interface TestLiveKitComponent : LiveKitComponent {

    fun websocketFactory(): MockWebSocketFactory

    fun rtcEngine(): RTCEngine

    fun networkCallbackRegistry(): MockNetworkCallbackRegistry

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance appContext: Context,
            coroutinesModule: TestCoroutinesModule,
        ): TestLiveKitComponent
    }
}
