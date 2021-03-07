package io.livekit.android.room

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions

class Room
@AssistedInject
constructor(
    @Assisted private val connectOptions: ConnectOptions,
    private val engine: RTCEngine,
) {

    suspend fun connect(url: String, token: String, isSecure: Boolean) {
        engine.join(url, token, isSecure)
    }

    @AssistedFactory
    interface Factory {
        fun create(connectOptions: ConnectOptions): Room
    }
}