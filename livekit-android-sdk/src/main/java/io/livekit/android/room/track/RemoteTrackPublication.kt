/*
 * Copyright 2023-2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room.track

import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.TrackEvent
import io.livekit.android.events.collect
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.util.debounce
import io.livekit.android.util.invoke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import livekit.LivekitModels
import javax.inject.Named

class RemoteTrackPublication(
    info: LivekitModels.TrackInfo,
    track: Track? = null,
    participant: RemoteParticipant,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    autoSubscribe: Boolean,
) : TrackPublication(info, track, participant) {

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
                            is TrackEvent.VisibilityChanged -> handleVisibilityChanged(it.isVisible)
                            is TrackEvent.VideoDimensionsChanged -> handleVideoDimensionsChanged(it.newDimensions)
                            is TrackEvent.StreamStateChanged -> handleStreamStateChanged(it)
                        }
                    }
                }

                if (value is RemoteVideoTrack && value.autoManageVideo) {
                    handleVideoDimensionsChanged(value.lastDimensions)
                    handleVisibilityChanged(value.lastVisibility)
                }
            }
        }

    private var trackJob: Job? = null

    var isDesired: Boolean = autoSubscribe
        private set
    private var disabled: Boolean = false
    private var videoQuality: VideoQuality? = VideoQuality.HIGH
    private var videoDimensions: Track.Dimensions? = null
    private var fps: Int? = null

    /**
     * The FlexFEC protection requested with [setFecProtection].
     */
    val fecProtection: FecProtection
        get() = requestedFecProtection ?: FecProtection.Disabled

    // null until requested, so updates leave the server's setting unchanged
    private var requestedFecProtection: FecProtection? = null

    var subscriptionAllowed: Boolean = true
        internal set

    val isAutoManaged: Boolean
        get() = (track as? RemoteVideoTrack)?.autoManageVideo ?: false

    /**
     * Returns true if track is subscribed, and ready for playback
     *
     * @see [subscriptionStatus]
     */
    override val subscribed: Boolean
        get() {
            if (!isDesired || !subscriptionAllowed) {
                return false
            }
            return super.subscribed
        }

    val subscriptionStatus: SubscriptionStatus
        get() {
            return if (!isDesired || track == null) {
                SubscriptionStatus.UNSUBSCRIBED
            } else if (!subscriptionAllowed) {
                SubscriptionStatus.SUBSCRIBED_AND_NOT_ALLOWED
            } else {
                SubscriptionStatus.SUBSCRIBED
            }
        }

    override var muted: Boolean
        get() = super.muted
        set(v) {
            if (super.muted == v) {
                return
            }
            super.muted = v
            val participant = this.participant.get() as? RemoteParticipant ?: return
            if (v) {
                participant.onTrackMuted(this)
            } else {
                participant.onTrackUnmuted(this)
            }
        }

    /**
     * Subscribe or unsubscribe from this track
     */
    fun setSubscribed(subscribed: Boolean) {
        isDesired = subscribed
        val participant = this.participant.get() as? RemoteParticipant ?: return
        val participantTracks = with(LivekitModels.ParticipantTracks.newBuilder()) {
            participantSid = participant.sid.value
            addTrackSids(sid)
            build()
        }
        participant.signalClient.sendUpdateSubscription(isDesired, participantTracks)
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
     * For tracks that support simulcasting, directly adjust subscribed quality
     *
     * This indicates the highest quality the client can accept. If network bandwidth does not
     * allow, server will automatically reduce quality to optimize for uninterrupted video.
     *
     * Will override previous calls to [setVideoDimensions].
     */
    fun setVideoQuality(quality: VideoQuality) {
        if (isAutoManaged ||
            !subscribed ||
            quality == videoQuality ||
            track !is VideoTrack
        ) {
            return
        }
        videoQuality = quality
        videoDimensions = null
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Update the dimensions that the server will use for determining the video quality to send down.
     *
     * Will override previous calls to [setVideoQuality].
     */
    fun setVideoDimensions(dimensions: Track.Dimensions) {
        if (isAutoManaged ||
            !subscribed ||
            videoDimensions == dimensions ||
            track !is VideoTrack
        ) {
            return
        }

        videoQuality = null
        videoDimensions = dimensions
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Update the fps that the server will use for determining the video quality to send down.
     */
    fun setVideoFps(fps: Int?) {
        if (isAutoManaged ||
            !subscribed ||
            this.fps == fps ||
            track !is VideoTrack
        ) {
            return
        }

        this.fps = fps
        sendUpdateTrackSettings.invoke()
    }

    /**
     * Requests proactive FlexFEC repair packets from the server for this video track.
     *
     * Repairs let the receiver recover lost packets without waiting for a retransmission, at the
     * cost of extra bandwidth. The protection changes without renegotiation, and is applied even
     * when the track's quality is managed by adaptive stream.
     *
     * Repairs are only sent when the server has downstream FlexFEC enabled and FlexFEC was
     * negotiated, which requires [io.livekit.android.LiveKit.enableFlexFec]. Otherwise the request
     * has no effect.
     *
     * ```
     * publication.setFecProtection(FecProtection.Minimum(FecProtectionLevel.MEDIUM))
     * ```
     */
    fun setFecProtection(protection: FecProtection) {
        if (!subscribed || track !is VideoTrack || protection == requestedFecProtection) {
            return
        }
        requestedFecProtection = protection
        sendUpdateTrackSettings.invoke()
    }

    private fun handleVisibilityChanged(isVisible: Boolean) {
        disabled = !isVisible
        sendUpdateTrackSettings.invoke()
    }

    private fun handleVideoDimensionsChanged(newDimensions: Track.Dimensions) {
        videoDimensions = newDimensions
        sendUpdateTrackSettings.invoke()
    }

    private fun handleStreamStateChanged(trackEvent: TrackEvent.StreamStateChanged) {
        participant.get()?.onTrackStreamStateChanged(trackEvent)
    }

    // Debounce just in case multiple settings get changed at once.
    internal val sendUpdateTrackSettings = debounce<Unit, Unit>(100L, CoroutineScope(ioDispatcher)) {
        sendUpdateTrackSettingsImpl()
    }

    private fun sendUpdateTrackSettingsImpl() {
        val participant = this.participant.get() as? RemoteParticipant ?: return

        val rtcTrack = track?.rtcTrack
        if (rtcTrack is livekit.org.webrtc.VideoTrack) {
            rtcTrack.setShouldReceive(!disabled)
        }

        participant.signalClient.sendUpdateTrackSettings(
            sid,
            disabled,
            videoDimensions,
            videoQuality?.toProto(),
            fps,
            requestedFecProtection?.toProto(),
        )
    }

    enum class SubscriptionStatus {
        /**
         * Has a valid track, receiving data.
         */
        SUBSCRIBED,

        /**
         * Has a track, but no data will be received due to permissions.
         */
        SUBSCRIBED_AND_NOT_ALLOWED,

        /**
         * Not subscribed.
         */
        UNSUBSCRIBED,
    }
}
