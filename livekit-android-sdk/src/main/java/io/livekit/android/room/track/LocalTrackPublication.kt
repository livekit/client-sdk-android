package io.livekit.android.room.track

import io.livekit.android.room.participant.LocalParticipant
import livekit.LivekitModels

class LocalTrackPublication(info: LivekitModels.TrackInfo,
                            track: Track? = null,
                            participant: LocalParticipant? = null) : TrackPublication(info, track, participant) {

    /**
     * Mute or unmute the current track. Muting the track would stop audio or video from being
     * transmitted to the server, and notify other participants in the room.
     */
    fun setMuted(muted: Boolean) {
        if (muted == this.muted) {
            return
        }

        val mediaTrack = track as? MediaTrack ?: return

        mediaTrack.rtcTrack.setEnabled(!muted)
        this.muted = muted

        // send updates to server
        val participant = this.participant.get() as? LocalParticipant ?: return

        participant.engine.updateMuteStatus(sid, muted)

        if (muted) {
            participant.listener?.onTrackMuted(this, participant)
            participant.internalListener?.onTrackMuted(this, participant)
        } else {
            participant.listener?.onTrackUnmuted(this, participant)
            participant.internalListener?.onTrackUnmuted(this, participant)
        }
    }
}
