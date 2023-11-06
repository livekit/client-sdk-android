/*
 * Copyright 2023 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android

import android.app.Application
import android.content.Context
import io.livekit.android.dagger.DaggerLiveKitComponent
import io.livekit.android.dagger.RTCModule
import io.livekit.android.dagger.create
import io.livekit.android.room.ProtocolVersion
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import timber.log.Timber

class LiveKit {
    companion object {
        /**
         * [LoggingLevel] to use for Livekit logs. Set to [LoggingLevel.OFF] to turn off logs.
         *
         * Defaults to [LoggingLevel.OFF]
         */
        @JvmStatic
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

        /**
         * Enables logs for the underlying WebRTC sdk logging. Used in conjunction with [loggingLevel].
         *
         * Note: WebRTC logging is very noisy and should only be used to diagnose native WebRTC issues.
         */
        @JvmStatic
        var enableWebRTCLogging: Boolean = false

        /**
         * Certain WebRTC classes need to be initialized prior to use.
         *
         * This does not need to be called under normal circumstances, as [LiveKit.create]
         * will handle this for you.
         */
        fun init(appContext: Context) {
            RTCModule.libWebrtcInitialization(appContext)
        }

        /**
         * Create a Room object.
         */
        fun create(
            appContext: Context,
            options: RoomOptions = RoomOptions(),
            overrides: LiveKitOverrides = LiveKitOverrides(),
        ): Room {
            val ctx = appContext.applicationContext

            if (ctx !is Application) {
                LKLog.w { "Application context was not found, this may cause memory leaks." }
            }

            val component = DaggerLiveKitComponent
                .factory()
                .create(ctx, overrides)

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
            room.dynacast = options.dynacast

            return room
        }

        /**
         * Connect to a LiveKit room
         * @param url URL to LiveKit server (i.e. ws://mylivekitdeploy.io)
         * @param listener Listener to Room events. LiveKit interactions take place with these callbacks
         */
        @Deprecated("Use LiveKit.create and Room.connect instead. This is limited to max protocol 7.")
        suspend fun connect(
            appContext: Context,
            url: String,
            token: String,
            options: ConnectOptions = ConnectOptions(),
            roomOptions: RoomOptions = RoomOptions(),
            listener: RoomListener? = null,
            overrides: LiveKitOverrides = LiveKitOverrides(),
        ): Room {
            val room = create(appContext, roomOptions, overrides)

            room.listener = listener

            val protocolVersion = maxOf(options.protocolVersion, ProtocolVersion.v7)
            val connectOptions = options.copy(protocolVersion = protocolVersion)

            room.connect(url, token, connectOptions)
            return room
        }
    }
}
