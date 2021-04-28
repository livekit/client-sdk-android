package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack

open class Track(
    name: String,
    kind: LivekitModels.TrackType
) {
    var name = name
        internal set
    var kind = kind
        internal set
    var sid: String? = null
        internal set

    open fun stop() {
        // subclasses override to provide stop behavior
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