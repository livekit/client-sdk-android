/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.room.participant

import io.livekit.android.events.ParticipantEvent
import io.livekit.android.room.SignalClient
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.LKLog
import io.livekit.android.webrtc.RTCStatsGetter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpReceiver
import org.webrtc.VideoTrack

class RemoteParticipant(
    sid: String,
    identity: String? = null,
    val signalClient: SignalClient,
    private val ioDispatcher: CoroutineDispatcher,
    defaultDispatcher: CoroutineDispatcher,
) : Participant(sid, identity, defaultDispatcher) {
    /**
     * Note: This constructor does not update all info due to event listener race conditions.
     *
     * Callers are responsible for calling through to [updateFromInfo] once ready.
     *
     * @suppress
     */
    constructor(
        info: LivekitModels.ParticipantInfo,
        signalClient: SignalClient,
        ioDispatcher: CoroutineDispatcher,
        defaultDispatcher: CoroutineDispatcher,
    ) : this(
        info.sid,
        info.identity,
        signalClient,
        ioDispatcher,
        defaultDispatcher,
    ) {
        super.updateFromInfo(info)
    }

    private val coroutineScope = CloseableCoroutineScope(defaultDispatcher + SupervisorJob())

    fun getTrackPublication(sid: String): RemoteTrackPublication? = tracks[sid] as? RemoteTrackPublication

    /**
     * @suppress
     */
    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        super.updateFromInfo(info)

        val validTrackPublication = mutableMapOf<String, RemoteTrackPublication>()
        val newTrackPublications = mutableMapOf<String, RemoteTrackPublication>()

        for (trackInfo in info.tracksList) {
            val trackSid = trackInfo.sid
            var publication = getTrackPublication(trackSid)

            if (publication == null) {
                publication = RemoteTrackPublication(
                    trackInfo,
                    participant = this,
                    ioDispatcher = ioDispatcher,
                )

                newTrackPublications[trackSid] = publication
                addTrackPublication(publication)
            } else {
                publication.updateFromInfo(trackInfo)
            }

            validTrackPublication[trackSid] = publication
        }

        for (publication in newTrackPublications.values) {
            internalListener?.onTrackPublished(publication, this)
            listener?.onTrackPublished(publication, this)
            eventBus.postEvent(ParticipantEvent.TrackPublished(this, publication), scope)
        }

        val invalidKeys = tracks.keys - validTrackPublication.keys
        for (invalidKey in invalidKeys) {
            val publication = tracks[invalidKey] ?: continue
            unpublishTrack(publication.sid, true)
        }
    }

    /**
     * @suppress
     */
    fun addSubscribedMediaTrack(
        mediaTrack: MediaStreamTrack,
        sid: String,
        statsGetter: RTCStatsGetter,
        receiver: RtpReceiver,
        autoManageVideo: Boolean = false,
        triesLeft: Int = 20,
    ) {
        val publication = getTrackPublication(sid)

        // We may receive subscribed tracks before publications come in. Retry until then.
        if (publication == null) {
            if (triesLeft == 0) {
                val message = "Could not find published track with sid: $sid"
                val exception = TrackException.InvalidTrackStateException(message)
                LKLog.e { "remote participant ${this.sid} --- $message" }

                internalListener?.onTrackSubscriptionFailed(sid, exception, this)
                listener?.onTrackSubscriptionFailed(sid, exception, this)
                eventBus.postEvent(ParticipantEvent.TrackSubscriptionFailed(this, sid, exception), scope)
            } else {
                coroutineScope.launch {
                    delay(150)
                    addSubscribedMediaTrack(mediaTrack, sid, statsGetter, receiver = receiver, autoManageVideo, triesLeft - 1)
                }
            }
            return
        }

        val track: Track = when (val kind = mediaTrack.kind()) {
            KIND_AUDIO -> RemoteAudioTrack(rtcTrack = mediaTrack as AudioTrack, name = "", receiver = receiver)
            KIND_VIDEO -> RemoteVideoTrack(
                rtcTrack = mediaTrack as VideoTrack,
                name = "",
                autoManageVideo = autoManageVideo,
                dispatcher = ioDispatcher,
                receiver = receiver,
            )

            else -> throw TrackException.InvalidTrackTypeException("invalid track type: $kind")
        }

        track.statsGetter = statsGetter

        publication.track = track
        publication.subscriptionAllowed = true
        track.name = publication.name
        track.sid = publication.sid

        addTrackPublication(publication)
        track.start()

        // TODO: how does mediatrack send ended event?

        internalListener?.onTrackSubscribed(track, publication, this)
        listener?.onTrackSubscribed(track, publication, this)
        eventBus.postEvent(ParticipantEvent.TrackSubscribed(this, track, publication), scope)
    }

    fun unpublishTrack(trackSid: String, sendUnpublish: Boolean = false) {
        val publication = tracks[trackSid] as? RemoteTrackPublication ?: return
        tracks = tracks.toMutableMap().apply { remove(trackSid) }

        val track = publication.track
        if (track != null) {
            try {
                track.stop()
            } catch (e: IllegalStateException) {
                // track may already be disposed, ignore.
            }
            internalListener?.onTrackUnsubscribed(track, publication, this)
            listener?.onTrackUnsubscribed(track, publication, this)
            eventBus.postEvent(ParticipantEvent.TrackUnsubscribed(this, track, publication), scope)
        }
        if (sendUnpublish) {
            internalListener?.onTrackUnpublished(publication, this)
            listener?.onTrackUnpublished(publication, this)
            eventBus.postEvent(ParticipantEvent.TrackUnpublished(this, publication), scope)
        }
        publication.track = null
    }

    internal fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        val pub = tracks[subscriptionPermissionUpdate.trackSid] as? RemoteTrackPublication ?: return

        if (pub.subscriptionAllowed != subscriptionPermissionUpdate.allowed) {
            pub.subscriptionAllowed = subscriptionPermissionUpdate.allowed

            eventBus.postEvent(
                ParticipantEvent.TrackSubscriptionPermissionChanged(this, pub, pub.subscriptionAllowed),
                coroutineScope,
            )
        }
    }

    // Internal methods just for posting events.
    internal fun onDataReceived(data: ByteArray, topic: String?) {
        listener?.onDataReceived(data, this)
        eventBus.postEvent(ParticipantEvent.DataReceived(this, data, topic), scope)
    }

    companion object {
        private const val KIND_AUDIO = "audio"
        private const val KIND_VIDEO = "video"
    }
}
