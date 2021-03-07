package io.livekit.android.dagger

import dagger.Module
import dagger.Provides
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import javax.inject.Singleton

@Module
class WebModule {
    companion object {
        @Provides
        @Singleton
        fun okHttpClient(): OkHttpClient {
            return OkHttpClient()
        }

        @Provides
        fun websocketFactory(okHttpClient: OkHttpClient): WebSocket.Factory {
            return okHttpClient
        }
    }
}