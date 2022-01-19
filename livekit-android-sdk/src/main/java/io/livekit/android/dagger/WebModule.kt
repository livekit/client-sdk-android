package io.livekit.android.dagger

import dagger.Module
import dagger.Provides
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import javax.annotation.Nullable
import javax.inject.Named
import javax.inject.Singleton

@Module
object WebModule {
    @Provides
    @Singleton
    fun okHttpClient(
        @Named(InjectionNames.OVERRIDE_OKHTTP)
        @Nullable
        okHttpClientOverride: OkHttpClient?
    ): OkHttpClient {
        return okHttpClientOverride ?: OkHttpClient()
    }

    @Provides
    fun websocketFactory(okHttpClient: OkHttpClient): WebSocket.Factory {
        return okHttpClient
    }
}