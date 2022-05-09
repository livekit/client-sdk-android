package io.livekit.android.videoencodedecode

import android.app.Application
import io.livekit.android.LiveKit
import io.livekit.android.util.LoggingLevel
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import timber.log.Timber

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LiveKit.loggingLevel = LoggingLevel.VERBOSE

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(this)
                .setInjectableLogger({ s, severity, s2 ->
                    val loggingLevel = when (severity) {
                        Logging.Severity.LS_VERBOSE -> LoggingLevel.VERBOSE
                        Logging.Severity.LS_INFO -> LoggingLevel.INFO
                        Logging.Severity.LS_WARNING -> LoggingLevel.WARN
                        Logging.Severity.LS_ERROR -> LoggingLevel.ERROR
                        else -> LoggingLevel.OFF
                    }
                    Timber.log(loggingLevel.toAndroidLogPriority(), "$s2: $s")
                }, Logging.Severity.LS_VERBOSE)
                .createInitializationOptions()
        )
    }
}