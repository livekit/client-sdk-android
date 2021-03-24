package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.AudioTrack

open class AudioTrack(name: String, override val rtcTrack: AudioTrack) :
    MediaTrack(name, LivekitModels.TrackType.AUDIO, rtcTrack) {

}