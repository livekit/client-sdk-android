package io.livekit.android.room.track

import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack

class Track(name: String, state: State) {

    var name = name
        internal set
    var state = state
        internal set

    inline class Sid(val sid: String)
    inline class Cid(val cid: String)

    enum class Priority {
        STANDARD, HIGH, LOW;
    }

    enum class State {
        ENDED, LIVE, NONE;
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

sealed class TrackException(message: String?, cause: Throwable?) : Exception(message, cause) {
    class InvalidTrackTypeException(message: String?, cause: Throwable?) :
        TrackException(message, cause)

    class DuplicateTrackException(message: String?, cause: Throwable?) :
        TrackException(message, cause)

    class InvalidTrackStateException(message: String?, cause: Throwable?) :
        TrackException(message, cause)

    class MediaException(message: String?, cause: Throwable?) : TrackException(message, cause)
    class PublishException(message: String?, cause: Throwable?) : TrackException(message, cause)
}