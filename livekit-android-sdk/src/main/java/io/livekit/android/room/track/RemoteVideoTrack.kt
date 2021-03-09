package io.livekit.android.room.track

class RemoteVideoTrack(
    override var sid: Sid,
    var switchedOff: Boolean = false,
    var priority: Priority? = null,
    name: String,
    rtcTrack: org.webrtc.VideoTrack
) : VideoTrack(name, rtcTrack), RemoteTrack