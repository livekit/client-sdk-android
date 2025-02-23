/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package io.livekit.android.room.track

import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.TrackEvent
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.RTCStatsGetter
import io.livekit.android.webrtc.getStats
import io.livekit.android.webrtc.peerconnection.executeBlockingOnRTCThread
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.RTCStatsCollectorCallback
import livekit.org.webrtc.RTCStatsReport
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

abstract class Track(
    name: String,
    kind: Kind,
    open val rtcTrack: MediaStreamTrack,
) {
    protected val eventBus = BroadcastEventBus<TrackEvent>()
    val events = eventBus.readOnly()
    var name = name
        internal set

    var kind = kind
        internal set
    var sid: String? = null
        internal set
    var streamState: StreamState by flowDelegate(StreamState.PAUSED) { newValue, oldValue ->
        if (newValue != oldValue) {
            eventBus.tryPostEvent(TrackEvent.StreamStateChanged(this, newValue))
        }
    }
        internal set

    var enabled: Boolean
        get() = withRTCTrack(defaultValue = false) { rtcTrack.enabled() }
        set(value) = withRTCTrack { rtcTrack.setEnabled(value) }

    var statsGetter: RTCStatsGetter? = null

    internal val isDisposed
        get() = rtcTrack.isDisposed

    /**
     * Return the [RTCStatsReport] for this track, or null if none is available.
     */
    suspend fun getRTCStats(): RTCStatsReport? = statsGetter?.getStats()

    /**
     * Calls the [callback] with the [RTCStatsReport] for this track, or null if none is available.
     */
    fun getRTCStats(callback: RTCStatsCollectorCallback) {
        statsGetter?.invoke(callback) ?: callback.onStatsDelivered(null)
    }

    enum class Kind(val value: String) {
        AUDIO("audio"),
        VIDEO("video"),

        // unknown
        UNRECOGNIZED("unrecognized"),
        ;

        fun toProto(): LivekitModels.TrackType {
            return when (this) {
                AUDIO -> LivekitModels.TrackType.AUDIO
                VIDEO -> LivekitModels.TrackType.VIDEO
                UNRECOGNIZED -> LivekitModels.TrackType.UNRECOGNIZED
            }
        }

        override fun toString(): String {
            return value
        }

        companion object {
            fun fromProto(tt: LivekitModels.TrackType): Kind {
                return when (tt) {
                    LivekitModels.TrackType.AUDIO -> AUDIO
                    LivekitModels.TrackType.VIDEO -> VIDEO
                    LivekitModels.TrackType.DATA, // TODO: does this need to be handled?
                    LivekitModels.TrackType.UNRECOGNIZED,
                    -> UNRECOGNIZED
                }
            }
        }
    }

    enum class Source {
        UNKNOWN,
        CAMERA,
        MICROPHONE,
        SCREEN_SHARE,
        SCREEN_SHARE_AUDIO,
        ;

        fun toProto(): LivekitModels.TrackSource {
            return when (this) {
                UNKNOWN -> LivekitModels.TrackSource.UNKNOWN
                CAMERA -> LivekitModels.TrackSource.CAMERA
                MICROPHONE -> LivekitModels.TrackSource.MICROPHONE
                SCREEN_SHARE -> LivekitModels.TrackSource.SCREEN_SHARE
                SCREEN_SHARE_AUDIO -> LivekitModels.TrackSource.SCREEN_SHARE_AUDIO
            }
        }

        companion object {
            fun fromProto(source: LivekitModels.TrackSource): Source {
                return when (source) {
                    LivekitModels.TrackSource.CAMERA -> CAMERA
                    LivekitModels.TrackSource.MICROPHONE -> MICROPHONE
                    LivekitModels.TrackSource.SCREEN_SHARE -> SCREEN_SHARE
                    LivekitModels.TrackSource.SCREEN_SHARE_AUDIO -> SCREEN_SHARE_AUDIO
                    LivekitModels.TrackSource.UNKNOWN,
                    LivekitModels.TrackSource.UNRECOGNIZED,
                    -> UNKNOWN
                }
            }
        }
    }

    enum class StreamState {
        ACTIVE,
        PAUSED,
        UNKNOWN,
        ;

        fun toProto(): LivekitRtc.StreamState {
            return when (this) {
                ACTIVE -> LivekitRtc.StreamState.ACTIVE
                PAUSED -> LivekitRtc.StreamState.PAUSED
                UNKNOWN -> LivekitRtc.StreamState.UNRECOGNIZED
            }
        }

        companion object {
            fun fromProto(state: LivekitRtc.StreamState): StreamState {
                return when (state) {
                    LivekitRtc.StreamState.ACTIVE -> ACTIVE
                    LivekitRtc.StreamState.PAUSED -> PAUSED
                    LivekitRtc.StreamState.UNRECOGNIZED -> UNKNOWN
                }
            }
        }
    }

    data class Dimensions(val width: Int, val height: Int)

    /**
     * Starts the track.
     */
    open fun start() {
        enabled = true
    }

    /**
     * Stops the track.
     */
    open fun stop() {
        enabled = false
    }

    /**
     * Disposes the track. LiveKit will generally take care of disposing tracks for you.
     */
    open fun dispose() {
        withRTCTrack {
            rtcTrack.dispose()
        }
    }

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withRTCTrack(crossinline action: MediaStreamTrack.() -> T) {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        withRTCTrack(Unit, action)
    }

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> withRTCTrack(defaultValue: T, crossinline action: MediaStreamTrack.() -> T): T {
        contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
        if (isDisposed) {
            return defaultValue
        }
        return executeBlockingOnRTCThread {
            return@executeBlockingOnRTCThread if (isDisposed) {
                defaultValue
            } else {
                action(rtcTrack)
            }
        }
    }
}

sealed class TrackException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class InvalidTrackTypeException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class DuplicateTrackException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class InvalidTrackStateException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class MediaException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)

    class PublishException(message: String? = null, cause: Throwable? = null) :
        TrackException(message, cause)
}

public const val KIND_AUDIO = "audio"
public const val KIND_VIDEO = "video"
