/*
 * Copyright 2023 LiveKit, Inc.
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

import dagger.Module
import dagger.Provides
import dagger.Reusable
import io.livekit.android.test.mock.MockWebSocketFactory
import io.livekit.android.stats.NetworkInfo
import io.livekit.android.stats.NetworkType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import javax.inject.Singleton

@Module
object TestWebModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor {
                // Don't make actual network calls
                Response.Builder()
                    .code(200)
                    .build()
            }
            .build()
    }

    @Provides
    @Singleton
    fun websocketFactory(webSocketFactory: MockWebSocketFactory): WebSocket.Factory {
        return webSocketFactory
    }

    @Provides
    @Singleton
    fun mockWebsocketFactory(): MockWebSocketFactory {
        return MockWebSocketFactory()
    }

    @Provides
    @Reusable
    fun networkInfo(): NetworkInfo {
        return object : NetworkInfo {
            override fun getNetworkType() = NetworkType.WIFI
        }
    }
}
