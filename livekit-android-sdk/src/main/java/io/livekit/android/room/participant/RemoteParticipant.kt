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

package io.livekit.android.room.participant

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.SignalClient
import io.livekit.android.room.datatrack.DataTrackSid
import io.livekit.android.room.datatrack.RemoteDataTrack
import io.livekit.android.room.track.KIND_AUDIO
import io.livekit.android.room.track.KIND_VIDEO
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import io.livekit.android.webrtc.RTCStatsGetter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.VideoTrack
import javax.inject.Named

/**
 * A representation of a remote participant.
 */
class RemoteParticipant(
    sid: Sid,
    identity: Identity? = null,
    internal val signalClient: SignalClient,
    private val ioDispatcher: CoroutineDispatcher,
    defaultDispatcher: CoroutineDispatcher,
    private val audioTrackFactory: RemoteAudioTrack.Factory,
    private val videoTrackFactory: RemoteVideoTrack.Factory,
) : Participant(sid, identity, defaultDispatcher) {
    /**
     * Note: This constructor does not update all info due to event listener race conditions.
     *
     * Callers are responsible for calling through to [updateFromInfo] once ready.
     *
     * @suppress
     */
    @AssistedInject
    constructor(
        @Assisted info: LivekitModels.ParticipantInfo,
        signalClient: SignalClient,
        @Named(InjectionNames.DISPATCHER_IO)
        ioDispatcher: CoroutineDispatcher,
        @Named(InjectionNames.DISPATCHER_DEFAULT)
        defaultDispatcher: CoroutineDispatcher,
        audioTrackFactory: RemoteAudioTrack.Factory,
        videoTrackFactory: RemoteVideoTrack.Factory,
    ) : this(
        Sid(info.sid),
        Identity(info.identity),
        signalClient,
        ioDispatcher,
        defaultDispatcher,
        audioTrackFactory,
        videoTrackFactory,
    ) {
        super.updateFromInfo(info)
    }

    @AssistedFactory
    interface Factory {
        fun create(
            info: LivekitModels.ParticipantInfo,
        ): RemoteParticipant
    }
    private val coroutineScope = CloseableCoroutineScope(defaultDispatcher + SupervisorJob())
    private val dataTracksLock = Any()

    /**
     * Data tracks published by this participant, keyed by track name.
     *
     * Names are the stable identifier: a track's SID rotates when the publisher republishes
     * after a full reconnect (the track object itself survives).
     *
     * ```
     * val track = participant.dataTracks["telemetry"]
     * track?.subscribe()?.onSuccess { stream ->
     *     stream.flow.collect { frame -> process(frame.payload) }
     * }
     * ```
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var dataTracks: Map<String, RemoteDataTrack> by flowDelegate(emptyMap())
        private set

    /**
     * Get a track publication with the corresponding sid.
     */
    fun getTrackPublication(sid: String): RemoteTrackPublication? = trackPublications[sid] as? RemoteTrackPublication

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
                    autoSubscribe = signalClient.lastOptions?.autoSubscribe ?: true
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
            eventBus.postEvent(ParticipantEvent.TrackPublished(this, publication), scope)
        }

        val invalidKeys = trackPublications.keys - validTrackPublication.keys
        for (invalidKey in invalidKeys) {
            val publication = trackPublications[invalidKey] ?: continue
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
            KIND_AUDIO -> audioTrackFactory.create(rtcTrack = mediaTrack as AudioTrack, name = "", receiver = receiver)
            KIND_VIDEO -> videoTrackFactory.create(
                rtcTrack = mediaTrack as VideoTrack,
                name = "",
                autoManageVideo = autoManageVideo,
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
        eventBus.postEvent(ParticipantEvent.TrackSubscribed(this, track, publication), scope)
    }

    fun unpublishTrack(trackSid: String, sendUnpublish: Boolean = false) {
        val publication = trackPublications[trackSid] as? RemoteTrackPublication ?: return
        trackPublications = trackPublications.toMutableMap().apply { remove(trackSid) }

        val track = publication.track
        if (track != null) {
            @Suppress("SwallowedException")
            try {
                track.stop()
            } catch (e: Exception) {
                // track may already be disposed, ignore.
            }
            internalListener?.onTrackUnsubscribed(track, publication, this)
            eventBus.postEvent(ParticipantEvent.TrackUnsubscribed(this, track, publication), scope)
        }
        if (sendUnpublish) {
            internalListener?.onTrackUnpublished(publication, this)
            eventBus.postEvent(ParticipantEvent.TrackUnpublished(this, publication), scope)
        }
        publication.track = null
    }

    internal fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        val pub = trackPublications[subscriptionPermissionUpdate.trackSid] as? RemoteTrackPublication ?: return

        if (pub.subscriptionAllowed != subscriptionPermissionUpdate.allowed) {
            pub.subscriptionAllowed = subscriptionPermissionUpdate.allowed

            eventBus.postEvent(
                ParticipantEvent.TrackSubscriptionPermissionChanged(this, pub, pub.subscriptionAllowed),
                coroutineScope,
            )
        }
    }

    internal fun onSubscriptionError(subscriptionResponse: LivekitRtc.SubscriptionResponse) {
        val trackSid = subscriptionResponse.trackSid
        if (trackPublications[trackSid] !is RemoteTrackPublication) {
            return
        }

        val exception = subscriptionErrorException(subscriptionResponse.err)
        internalListener?.onTrackSubscriptionFailed(trackSid, exception, this)
        eventBus.postEvent(ParticipantEvent.TrackSubscriptionFailed(this, trackSid, exception), scope)
    }

    private fun subscriptionErrorException(error: LivekitModels.SubscriptionError): TrackException {
        return when (error) {
            LivekitModels.SubscriptionError.SE_CODEC_UNSUPPORTED -> TrackException.MediaException("Codec not supported")
            LivekitModels.SubscriptionError.SE_TRACK_NOTFOUND -> TrackException.InvalidTrackStateException("Track not found")
            LivekitModels.SubscriptionError.SE_UNKNOWN,
            LivekitModels.SubscriptionError.UNRECOGNIZED,
            -> TrackException.InvalidTrackStateException("Subscription failed")
        }
    }

    // Internal methods just for posting events.
    internal fun onDataReceived(event: RoomEvent.DataReceived) {
        eventBus.postEvent(ParticipantEvent.DataReceived(this, event.data, event.topic, event.encryptionType), scope)
    }

    /**
     * Adds the track, returning `false` if this exact track is already attached.
     */
    internal fun addDataTrack(track: RemoteDataTrack): Boolean {
        val attached = synchronized(dataTracksLock) {
            if (dataTracks.values.any { it === track }) {
                return@synchronized false
            }
            dataTracks = dataTracks + (track.name to track)
            true
        }
        if (attached) {
            eventBus.postEvent(ParticipantEvent.DataTrackPublished(this, track), scope)
        }
        return attached
    }

    internal fun removeDataTrack(sid: DataTrackSid): RemoteDataTrack? {
        // `info.sid` is an FFI call; resolve the instance before taking the lock.
        val track = dataTracks.values.firstOrNull { it.info.sid == sid } ?: return null
        synchronized(dataTracksLock) {
            if (dataTracks.values.none { it === track }) {
                return null
            }
            dataTracks = dataTracks - track.name
            return track
        }
    }

    /**
     * Removes the track and emits [ParticipantEvent.DataTrackUnpublished], even if it was not
     * attached (for example after a full reconnect detached it).
     */
    internal fun unpublishDataTrack(sid: DataTrackSid) {
        removeDataTrack(sid)
        eventBus.postEvent(ParticipantEvent.DataTrackUnpublished(this, sid), scope)
    }

    /**
     * Unpublishes every attached data track and emits an unpublish event for each.
     *
     * @return The SIDs that were unpublished, for the room to emit matching [io.livekit.android.events.RoomEvent]s.
     */
    internal fun unpublishDataTracks(): List<DataTrackSid> {
        val previous = synchronized(dataTracksLock) {
            dataTracks.also { dataTracks = emptyMap() }
        }
        val sids = previous.values.map { it.info.sid }
        for (sid in sids) {
            eventBus.postEvent(ParticipantEvent.DataTrackUnpublished(this, sid), scope)
        }
        return sids
    }

    /**
     * Drops attached data tracks without notifying. Used when the tracks outlive this participant
     * object: a full reconnect recreates participants, but the incoming manager keeps its tracks
     * and re-attaches them.
     */
    internal fun detachDataTracks() {
        synchronized(dataTracksLock) {
            dataTracks = emptyMap()
        }
    }
}
