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

package io.livekit.android.room.util

import io.livekit.android.util.FlowObservable
import io.livekit.android.util.flow
import io.livekit.android.webrtc.isConnected
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import livekit.org.webrtc.PeerConnection.PeerConnectionState

internal interface PeerConnectionStateObservable {
    @FlowObservable
    @get:FlowObservable
    val connectionState: PeerConnectionState
}

/**
 * Waits until the connection state [PeerConnectionState.isConnected].
 */
internal suspend fun PeerConnectionStateObservable.waitUntilConnected() {
    this::connectionState.flow
        .takeWhile {
            !it.isConnected()
        }
        .collect()
}
