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

package io.livekit.android.test.mock

import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkRequest
import io.livekit.android.room.network.NetworkCallbackRegistry

class MockNetworkCallbackRegistry : NetworkCallbackRegistry {
    val networkCallbacks = mutableSetOf<NetworkCallback>()
    override fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback) {
        networkCallbacks.add(networkCallback)
    }

    override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
        networkCallbacks.remove(networkCallback)
    }
}
