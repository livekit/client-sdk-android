package io.livekit.android.room.track

import org.webrtc.MediaStreamTrack
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

open class VideoTrack(name: String, val rtcTrack: VideoTrack) :
    Track(name, stateFromRTCMediaTrackState(rtcTrack.state())),
    MediaTrack {

    override val mediaTrack: MediaStreamTrack
        get() = rtcTrack

    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    fun addRenderer(renderer: VideoSink) = rtcTrack.addSink(renderer)

    fun removeRenderer(renderer: VideoSink) = rtcTrack.addSink(renderer)
}