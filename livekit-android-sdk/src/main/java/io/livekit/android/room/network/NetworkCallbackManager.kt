/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.room.network

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.livekit.android.util.LKLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

typealias NetworkCallbackManagerFactory = @JvmSuppressWildcards (
    networkCallback: NetworkCallback,
) -> NetworkCallbackManager

/**
 * @suppress
 */
interface NetworkCallbackRegistry {
    fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback)
    fun unregisterNetworkCallback(networkCallback: NetworkCallback)
}

internal class NetworkCallbackRegistryImpl(val connectivityManager: ConnectivityManager) : NetworkCallbackRegistry {
    override fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback) {
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}

/**
 * @suppress
 */
interface NetworkCallbackManager : Closeable {
    fun registerCallback()
    fun unregisterCallback()
}

/**
 * Manages a [ConnectivityManager.NetworkCallback] so that it is never
 * registered multiple times. A NetworkCallback is allowed to be registered
 * multiple times by the ConnectivityService, but the underlying network
 * requests will leak on 8.0 and earlier.
 *
 * There's a 100 request hard limit, so leaks here are particularly dangerous.
 *
 * @suppress
 */
class NetworkCallbackManagerImpl(
    private val networkCallback: NetworkCallback,
    private val connectivityManager: NetworkCallbackRegistry,
) : NetworkCallbackManager {
    private val isRegistered = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    @Synchronized
    override fun registerCallback() {
        if (!isClosed.get() && isRegistered.compareAndSet(false, true)) {
            try {
                val networkRequest = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            } catch (e: Exception) {
                LKLog.w(e) { "Exception when trying to register network callback, reconnection may be impaired." }
            }
        }
    }

    @Synchronized
    override fun unregisterCallback() {
        if (!isClosed.get() && isRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                // do nothing, may happen on older versions if attempting to unregister twice.
                // This shouldn't happen though, so log it just in case.
                LKLog.w { "NetworkCallback was unregistered multiple times?" }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed.get()) {
            return
        }

        if (isRegistered.get()) {
            unregisterCallback()
        }

        isClosed.set(true)
    }
}
