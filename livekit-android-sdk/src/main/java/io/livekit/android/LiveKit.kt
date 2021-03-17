package io.livekit.android

import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.room.Room

class LiveKit {
    companion object {
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions,
            listener: Room.Listener?
        ): Room {

            val component = DaggerLiveKitComponent
                .factory()
                .create(appContext)

            val room = component.roomFactory()
                .create(options)
            room.listener = listener
            room.connect(url, token, options.isSecure)

            return room
        }
    }
}
