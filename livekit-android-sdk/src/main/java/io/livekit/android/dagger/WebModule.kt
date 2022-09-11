package io.livekit.android.dagger

import android.content.Context
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import dagger.Reusable
import io.livekit.android.stats.AndroidNetworkInfo
import io.livekit.android.stats.NetworkInfo
import okhttp3.OkHttpClient
import okhttp3.WebSocket
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

    @Provides
    @Reusable
    fun networkInfo(context: Context): NetworkInfo {
        return AndroidNetworkInfo(context)
    }
}