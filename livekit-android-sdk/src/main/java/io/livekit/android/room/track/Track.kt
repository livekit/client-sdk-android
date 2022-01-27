package io.livekit.android.room.track

import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.TrackEvent
import io.livekit.android.util.flowDelegate
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.MediaStreamTrack

open class Track(
    name: String,
    kind: Kind,
    open val rtcTrack: MediaStreamTrack
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

    enum class Kind(val value: String) {
        AUDIO("audio"),
        VIDEO("video"),

        // unknown
        UNRECOGNIZED("unrecognized");

        fun toProto(): LivekitModels.TrackType {
            return when (this) {
                AUDIO -> LivekitModels.TrackType.AUDIO
                VIDEO -> LivekitModels.TrackType.VIDEO
                UNRECOGNIZED -> LivekitModels.TrackType.UNRECOGNIZED
            }
        }

        override fun toString(): String {
            return value;
        }

        companion object {
            fun fromProto(tt: LivekitModels.TrackType): Kind {
                return when (tt) {
                    LivekitModels.TrackType.AUDIO -> AUDIO
                    LivekitModels.TrackType.VIDEO -> VIDEO
                    else -> UNRECOGNIZED
                }
            }
        }
    }

    enum class Source {
        CAMERA,
        MICROPHONE,
        SCREEN_SHARE,
        UNKNOWN;


        fun toProto(): LivekitModels.TrackSource {
            return when (this) {
                CAMERA -> LivekitModels.TrackSource.CAMERA
                MICROPHONE -> LivekitModels.TrackSource.MICROPHONE
                SCREEN_SHARE -> LivekitModels.TrackSource.SCREEN_SHARE
                UNKNOWN -> LivekitModels.TrackSource.UNKNOWN
            }
        }

        companion object {
            fun fromProto(source: LivekitModels.TrackSource): Source {
                return when (source) {
                    LivekitModels.TrackSource.CAMERA -> CAMERA
                    LivekitModels.TrackSource.MICROPHONE -> MICROPHONE
                    LivekitModels.TrackSource.SCREEN_SHARE -> SCREEN_SHARE
                    else -> UNKNOWN
                }
            }
        }
    }

    enum class StreamState {
        ACTIVE,
        PAUSED,
        UNKNOWN;

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

    open fun start() {
        rtcTrack.setEnabled(true)
    }

    open fun stop() {
        rtcTrack.setEnabled(false)
    }

    open fun dispose() {
        rtcTrack.dispose()
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