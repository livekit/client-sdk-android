package io.livekit.android

import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent

class LiveKit {
    companion object {
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions
        ) {

            val component = DaggerLiveKitComponent
                .factory()
                .create(appContext)

            val room = component.roomFactory()
                .create(options)
            room.connect(url, token, false)
        }
    }
}
