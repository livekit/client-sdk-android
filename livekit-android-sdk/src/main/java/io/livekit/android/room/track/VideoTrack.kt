package io.livekit.android.room.track

import org.webrtc.VideoSink
import org.webrtc.VideoTrack

open class VideoTrack(name: String, override val rtcTrack: VideoTrack) :
    Track(name, Kind.VIDEO, rtcTrack) {
    protected val sinks: MutableList<VideoSink> = ArrayList();

    var enabled: Boolean
        get() = rtcTrack.enabled()
        set(value) {
            rtcTrack.setEnabled(value)
        }

    open fun addRenderer(renderer: VideoSink) {
        sinks.add(renderer)
        rtcTrack.addSink(renderer)
    }

    open fun removeRenderer(renderer: VideoSink) {
        rtcTrack.removeSink(renderer)
        sinks.remove(renderer)
    }

    override fun stop() {
        for (sink in sinks) {
            rtcTrack.removeSink(sink)
        }
        sinks.clear()
        super.stop()
    }
}
