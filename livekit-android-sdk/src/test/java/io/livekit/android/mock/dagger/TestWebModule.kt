package io.livekit.android.mock.dagger

import dagger.Module
import dagger.Provides
import io.livekit.android.mock.MockWebsocketFactory
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
    fun websocketFactory(websocketFactory: MockWebsocketFactory): WebSocket.Factory {
        return websocketFactory
    }

    @Provides
    @Singleton
    fun mockWebsocketFactory(): MockWebsocketFactory {
        return MockWebsocketFactory()
    }
}