package io.livekit.android.room.track

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.TrackPublishOptions
import livekit.LivekitModels

class LocalTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track,
    participant: LocalParticipant,
    val options: TrackPublishOptions,
) : TrackPublication(info, track, participant) {

    /**
     * Mute or unmute the current track. Muting the track would stop audio or video from being
     * transmitted to the server, and notify other participants in the room.
     */
    override var muted: Boolean
        get() = super.muted
        set(muted) {
            if (muted == this.muted) {
                return
            }

            val mediaTrack = track ?: return

            mediaTrack.rtcTrack.setEnabled(!muted)
            super.muted = muted

            // send updates to server
            val participant = this.participant.get() as? LocalParticipant ?: return

            participant.engine.updateMuteStatus(sid, muted)

            if (muted) {
                participant.onTrackMuted(this)
            } else {
                participant.onTrackUnmuted(this)
            }
        }
}
