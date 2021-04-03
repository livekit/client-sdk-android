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
    var state: State = State.NONE
    var sid: String? = null
        internal set

    enum class State {
        ENDED, LIVE, NONE;
    }

    open fun stop() {
        // subclasses override to provide stop behavior
    }

    companion object {
        fun stateFromRTCMediaTrackState(trackState: MediaStreamTrack.State): State {
            return when (trackState) {
                MediaStreamTrack.State.ENDED -> State.ENDED
                MediaStreamTrack.State.LIVE -> State.LIVE
            }
        }

        fun stateFromRTCDataChannelState(dataChannelState: DataChannel.State): State {
            return when (dataChannelState) {
                DataChannel.State.CONNECTING,
                DataChannel.State.OPEN -> {
                    State.LIVE
                }
                DataChannel.State.CLOSING,
                DataChannel.State.CLOSED -> {
                    State.ENDED
                }
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