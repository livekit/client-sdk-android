package io.livekit.android.room.track

import livekit.LivekitModels
import org.webrtc.MediaStreamTrack


open class MediaTrack(name: String, kind: LivekitModels.TrackType, open val rtcTrack: MediaStreamTrack) :
    Track(name, kind) {

    // TODO: how do we mute/disable a track

    override fun stop() {
        rtcTrack.setEnabled(false)
        rtcTrack.dispose()
    }
}
