package io.livekit.android.mock

import org.webrtc.VideoSink
import org.webrtc.VideoTrack

class MockVideoStreamTrack(
    val id: String = "id",
    val kind: String = VIDEO_TRACK_KIND,
    var enabled: Boolean = true,
    var state: State = State.LIVE,
) : VideoTrack(1L) {
    val sinks = mutableSetOf<VideoSink>()

    private var shouldReceive = true

    override fun id(): String = id

    override fun kind(): String = kind

    override fun enabled(): Boolean = enabled

    override fun setEnabled(enable: Boolean): Boolean {
        enabled = enable
        return true
    }

    override fun shouldReceive() = shouldReceive

    override fun setShouldReceive(shouldReceive: Boolean) {
        this.shouldReceive = shouldReceive
    }

    override fun state(): State {
        return state
    }

    override fun dispose() {
    }

    override fun addSink(sink: VideoSink) {
        sinks.add(sink)
    }

    override fun removeSink(sink: VideoSink) {
        sinks.remove(sink)
    }
}
