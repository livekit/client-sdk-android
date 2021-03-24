package io.livekit.android.room.track

import org.webrtc.DataChannel

class RemoteDataTrack(
    override var sid: String,
    name: String,
    rtcTrack: DataChannel
) :
    DataTrack(name, rtcTrack),
    RemoteTrack {

    var listener: Listener? = null

    interface Listener {
        fun onReceiveString(message: String, dataTrack: DataTrack)
        fun onReceiveData(message: DataChannel.Buffer, dataTrack: DataTrack)
    }
}