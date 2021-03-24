package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.DataChannel

open class DataTrack(
    name: String,
    var dataChannel: DataChannel? = null
) : Track(name, LivekitModels.TrackType.DATA) {
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

    override fun stop() {
        dataChannel?.unregisterObserver()
    }
}
