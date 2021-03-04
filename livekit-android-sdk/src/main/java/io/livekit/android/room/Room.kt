package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions

class Room
@AssistedInject
constructor(
    private val connectOptions: ConnectOptions,
    @Assisted private val engine: RTCEngine,
) {

    suspend fun connect(url: String, token: String, isSecure: Boolean) {
        engine.join(url, token, isSecure)
    }
}