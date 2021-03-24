package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

open class VideoTrack(name: String, override val rtcTrack: VideoTrack) :
    MediaTrack(name, LivekitModels.TrackType.VIDEO, rtcTrack){

    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    fun addRenderer(renderer: VideoSink) = rtcTrack.addSink(renderer)

    fun removeRenderer(renderer: VideoSink) = rtcTrack.addSink(renderer)
}
