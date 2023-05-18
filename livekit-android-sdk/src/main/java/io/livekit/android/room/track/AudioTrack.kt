package io.livekit.android.room.track

import org.webrtc.AudioTrack

abstract class AudioTrack(name: String, override val rtcTrack: AudioTrack) :
    Track(name, Kind.AUDIO, rtcTrack) {
}
