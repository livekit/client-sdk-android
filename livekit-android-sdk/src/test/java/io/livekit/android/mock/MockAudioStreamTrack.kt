package io.livekit.android.mock

import org.webrtc.AudioTrack

class MockAudioStreamTrack(
    val id: String = "id",
    val kind: String = AUDIO_TRACK_KIND,
    var enabled: Boolean = true,
    var state: State = State.LIVE,
) : AudioTrack(1L) {
    override fun id(): String = id

    override fun kind(): String = kind

    override fun enabled(): Boolean = enabled

    override fun setEnabled(enable: Boolean): Boolean {
        enabled = enable
        return true
    }

    override fun state(): State {
        return state
    }

    override fun dispose() {
    }

    override fun setVolume(volume: Double) {
    }
}