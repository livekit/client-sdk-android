package io.livekit.android

import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import timber.log.Timber

class LiveKit {
    companion object {
        var loggingLevel: LoggingLevel
            get() = LKLog.loggingLevel
            set(value) {
                LKLog.loggingLevel = value

                // Plant debug tree if needed.
                if (value != LoggingLevel.OFF) {
                    val forest = Timber.forest()
                    val needsPlanting = forest.none { it is Timber.DebugTree }

                    if (needsPlanting) {
                        Timber.plant(Timber.DebugTree())
                    }
                }
            }

        fun create(
            appContext: Context,
            options: RoomOptions = RoomOptions(),
        ): Room {
            val ctx = appContext.applicationContext
            val component = DaggerLiveKitComponent
                .factory()
                .create(ctx)

            val room = component.roomFactory().create(ctx)

            options.audioTrackCaptureDefaults?.let {
                room.audioTrackCaptureDefaults = it
            }
            options.videoTrackCaptureDefaults?.let {
                room.videoTrackCaptureDefaults = it
            }

            options.audioTrackPublishDefaults?.let {
                room.audioTrackPublishDefaults = it
            }
            options.videoTrackPublishDefaults?.let {
                room.videoTrackPublishDefaults = it
            }
            room.adaptiveStream = options.adaptiveStream

            return room
        }

        /**
         * Connect to a LiveKit room
         * @param url URL to LiveKit server (i.e. ws://mylivekitdeploy.io)
         * @param listener Listener to Room events. LiveKit interactions take place with these callbacks
         */
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions = ConnectOptions(),
            roomOptions: RoomOptions = RoomOptions(),
            listener: RoomListener?
        ): Room {
            val room = create(appContext, roomOptions)

            room.listener = listener
            room.connect(url, token, options)
            return room
        }

    }
}
