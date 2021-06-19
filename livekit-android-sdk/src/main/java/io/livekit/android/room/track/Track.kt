package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack

open class Track(
    name: String,
    kind: Kind,
    open val rtcTrack: MediaStreamTrack
) {
    var name = name
        internal set
    var kind = kind
        internal set
    var sid: String? = null
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

    class Dimensions(var width: Int, var height: Int)

    open fun stop() {
        rtcTrack.setEnabled(false)
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