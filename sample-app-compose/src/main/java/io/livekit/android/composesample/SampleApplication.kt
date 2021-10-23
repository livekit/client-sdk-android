package io.livekit.android.composesample

import android.app.Application
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LiveKit.loggingLevel = LoggingLevel.VERBOSE
    }
}