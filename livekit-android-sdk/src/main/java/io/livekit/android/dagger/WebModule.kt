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

package io.livekit.android.dagger

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import dagger.Reusable
import io.livekit.android.memory.CloseableManager
import io.livekit.android.room.network.NetworkCallbackManagerFactory
import io.livekit.android.room.network.NetworkCallbackManagerImpl
import io.livekit.android.stats.AndroidNetworkInfo
import io.livekit.android.stats.NetworkInfo
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import javax.inject.Named
import javax.inject.Singleton

@Module
internal object WebModule {
    @Provides
    @Singleton
    fun okHttpClient(
        @Named(InjectionNames.OVERRIDE_OKHTTP)
        @Nullable
        okHttpClientOverride: OkHttpClient?,
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

    @Provides
    fun connectivityManager(context: Context): ConnectivityManager {
        return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    @Reusable
    fun networkCallbackManagerFactory(closeableManager: CloseableManager, connectivityManager: ConnectivityManager): NetworkCallbackManagerFactory {
        return { networkCallback: ConnectivityManager.NetworkCallback ->
            NetworkCallbackManagerImpl(networkCallback, connectivityManager)
                .apply { closeableManager.registerClosable(this) }
        }
    }
}
