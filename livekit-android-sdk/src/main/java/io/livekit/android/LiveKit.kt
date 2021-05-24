package io.livekit.android

import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener

class LiveKit {
    companion object {
        /**
         * Connect to a LiveKit room
         * @param url URL to LiveKit server (i.e. ws://mylivekitdeploy.io)
         * @param listener Listener to Room events. LiveKit interactions take place with these callbacks
         */
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions?,
            listener: RoomListener?
        ): Room {
            val ctx = appContext.applicationContext
            val component = DaggerLiveKitComponent
                .factory()
                .create(ctx)

            val room = component.roomFactory()
                .create(ctx)
            room.listener = listener
            room.connect(url, token, options)

            return room
        }

    }
}
