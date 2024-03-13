package io.livekit.android.mock

import io.livekit.android.room.provisions.LKObjects

object MockLKObjects {
    fun get(): LKObjects {
        return LKObjects(
            eglBaseProvider = { MockEglBase() },
        )
    }
}
