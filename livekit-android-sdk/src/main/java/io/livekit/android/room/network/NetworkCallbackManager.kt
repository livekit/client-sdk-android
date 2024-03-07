package io.livekit.android.room.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.livekit.android.util.LKLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a [ConnectivityManager.NetworkCallback] so that it is never
 * registered multiple times. A NetworkCallback is allowed to be registered
 * multiple times by the ConnectivityService, but the underlying network
 * requests will leak on 8.0 and earlier.
 *
 * There's a 100 request hard limit, so leaks here are particularly dangerous.
 */
class NetworkCallbackManager(
    private val networkCallback: ConnectivityManager.NetworkCallback,
    private val connectivityManager: ConnectivityManager,
) : Closeable {
    private val isRegistered = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    @Synchronized
    fun registerCallback() {
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
    fun unregisterCallback() {
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
