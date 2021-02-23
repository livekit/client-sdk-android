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

    suspend fun connect() {
        engine.join(connectOptions)
    }
}