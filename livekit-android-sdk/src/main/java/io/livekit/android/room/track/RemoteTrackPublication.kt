package io.livekit.android.room.track

import io.livekit.android.room.participant.RemoteParticipant
import livekit.LivekitModels
import livekit.LivekitRtc

class RemoteTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track? = null,
    participant: RemoteParticipant
) : TrackPublication(info, track, participant) {

    private var unsubscribed: Boolean = false
    private var disabled: Boolean = false
    private var videoQuality: LivekitRtc.VideoQuality = LivekitRtc.VideoQuality.HIGH

    override val subscribed: Boolean
        get() {
            if (unsubscribed) {
                return false
            }
            return super.subscribed
        }
    override var muted: Boolean = false
        set(v) {
            if (field == v) {
                return
            }
            field = v
            val participant = this.participant.get() as? RemoteParticipant ?: return
            if (v) {
                participant.listener?.onTrackMuted(this, participant)
                participant.internalListener?.onTrackMuted(this, participant)
            } else {
                participant.listener?.onTrackUnmuted(this, participant)
                participant.internalListener?.onTrackUnmuted(this, participant)
            }
        }

    /**
     * subscribe or unsubscribe from this track
     */
    fun setSubscribed(subscribed: Boolean) {
        unsubscribed = !subscribed
        val participant = this.participant.get() as? RemoteParticipant ?: return

        participant.rtcClient.sendUpdateSubscription(sid, !unsubscribed, videoQuality)
    }

    /**
     * disable server from sending down data for this track
     *
     * this is useful when the participant is off screen, you may disable streaming down their
     * video to reduce bandwidth requirements
     */
    fun setEnabled(enabled: Boolean) {
        disabled = !enabled
        sendUpdateTrackSettings()
    }

    /**
     * for tracks that support simulcasting, adjust subscribed quality
     *
     * this indicates the highest quality the client can accept. if network bandwidth does not
     * allow, server will automatically reduce quality to optimize for uninterrupted video
     */
    fun setVideoQuality(quality: LivekitRtc.VideoQuality) {
        videoQuality = quality
        sendUpdateTrackSettings()
    }

    private fun sendUpdateTrackSettings() {
        val participant = this.participant.get() as? RemoteParticipant ?: return

        participant.rtcClient.sendUpdateTrackSettings(sid, disabled, videoQuality)
    }
}