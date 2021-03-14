package io.livekit.android.room.track

import org.webrtc.DataChannel

open class DataTrack(
    name: String,
    val rtcTrack: DataChannel
) : Track(name, stateFromRTCDataChannelState(rtcTrack.state())) {

    /**
     * TODO: These values are only available at [DataChannel.Init]
     */
    val ordered: Boolean = TODO()
    val maxPacketLifeTime: Int = TODO()
    val maxRetransmits: Int = TODO()
    
}