package io.livekit.android.room.track

import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack

open class AudioTrack(name: String, val rtcTrack: AudioTrack) :
    Track(name, stateFromRTCMediaTrackState(rtcTrack.state())),
    MediaTrack {

    override val mediaTrack: MediaStreamTrack
        get() = rtcTrack
}