package io.livekit.android.room.track

import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.TrackEvent
import io.livekit.android.events.collect
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.util.debounce
import io.livekit.android.util.invoke
import kotlinx.coroutines.*
import livekit.LivekitModels
import livekit.LivekitRtc
import javax.inject.Named

class RemoteTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track? = null,
    participant: RemoteParticipant,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
) : TrackPublication(info, track, participant) {

    @OptIn(FlowPreview::class)
    override var track: Track?
        get() = super.track
        set(value) {
            if (value != super.track) {
                trackJob?.cancel()
                trackJob = null
            }

            super.track = value

            if (value != null) {
                trackJob = CoroutineScope(ioDispatcher).launch {
                    value.events.collect {
                        when (it) {
                            is TrackEvent.VisibilityChanged -> handleVisibilityChanged(it)
                            is TrackEvent.VideoDimensionsChanged -> handleVideoDimensionsChanged(it)
                        }
                    }
                }
            }
        }

    private fun handleVisibilityChanged(trackEvent: TrackEvent.VisibilityChanged) {
        disabled = !trackEvent.isVisible
        sendUpdateTrackSettings.invoke()
    }

    private fun handleVideoDimensionsChanged(trackEvent: TrackEvent.VideoDimensionsChanged) {
        videoDimensions = trackEvent.newDimensions
        sendUpdateTrackSettings.invoke()
    }

    private var trackJob: Job? = null

    private var unsubscribed: Boolean = false
    private var disabled: Boolean = false
    private var videoQuality: LivekitRtc.VideoQuality? = LivekitRtc.VideoQuality.HIGH
    private var videoDimensions: Track.Dimensions? = null

    val isAutoManaged: Boolean
        get() = (track as? RemoteVideoTrack)?.autoManageVideo ?: false

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

        participant.signalClient.sendUpdateSubscription(sid, !unsubscribed)
    }

    /**
     * disable server from sending down data for this track
     *
     * this is useful when the participant is off screen, you may disable streaming down their
     * video to reduce bandwidth requirements
     */
    fun setEnabled(enabled: Boolean) {
        if (isAutoManaged || !subscribed || enabled == !disabled) {
            return
        }
        disabled = !enabled
        sendUpdateTrackSettings.invoke()
    }

    /**
     * for tracks that support simulcasting, directly adjust subscribed quality
     *
     * this indicates the highest quality the client can accept. if network bandwidth does not
     * allow, server will automatically reduce quality to optimize for uninterrupted video
     */
    fun setVideoQuality(quality: LivekitRtc.VideoQuality) {
        if (isAutoManaged
            || !subscribed
            || quality == videoQuality
            || track !is VideoTrack
        ) {
            return
        }
        videoQuality = quality
        videoDimensions = null
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Update the dimensions that the server will use for determining the video quality to send down.
     */
    fun setVideoDimensions(dimensions: Track.Dimensions) {
        if (isAutoManaged
            || !subscribed
            || videoDimensions == dimensions
            || track !is VideoTrack
        ) {
            return
        }

        videoQuality = null
        videoDimensions = dimensions
        sendUpdateTrackSettings.invoke()
    }

    // Debounce just in case multiple settings get changed at once.
    private val sendUpdateTrackSettings = debounce<Unit, Unit>(100L, CoroutineScope(ioDispatcher)) {
        sendUpdateTrackSettingsImpl()
    }

    private fun sendUpdateTrackSettingsImpl() {
        val participant = this.participant.get() as? RemoteParticipant ?: return

        participant.signalClient.sendUpdateTrackSettings(
            sid,
            disabled,
            videoDimensions,
            videoQuality
        )
    }
}