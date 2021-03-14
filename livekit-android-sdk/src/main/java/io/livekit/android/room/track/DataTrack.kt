package io.livekit.android.room.track

import org.webrtc.DataChannel

open class DataTrack(
    name: String,
    var rtcTrack: DataChannel? = null
) : Track(
    name,
    run {
        if (rtcTrack != null) {
            stateFromRTCDataChannelState(rtcTrack.state())
        } else {
            State.NONE
        }
    }) {

    var ordered: Boolean = TODO()
        private set
    var maxRetransmitTimeMs: Int = TODO()
        private set
    var maxRetransmits: Int = TODO()
        private set

    fun updateConfig(config: DataChannel.Init) {
        ordered = config.ordered
        maxRetransmitTimeMs = config.maxRetransmitTimeMs
        maxRetransmits = config.maxRetransmits
    }
}