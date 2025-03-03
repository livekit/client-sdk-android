/*
 * Copyright 2023-2025 LiveKit, Inc.
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

import androidx.annotation.VisibleForTesting
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.BroadcastEventBus
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.TrackEvent
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.diffMapChange
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import livekit.LivekitModels
import java.util.Date
import javax.inject.Named

open class Participant(
    var sid: Sid,
    identity: Identity? = null,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val coroutineDispatcher: CoroutineDispatcher,
) {

    @Serializable
    @JvmInline
    value class Identity(val value: String)

    @Serializable
    @JvmInline
    value class Sid(val value: String)

    /**
     * To only be used for flow delegate scoping, and should not be cancelled.
     **/
    private val delegateScope = createScope()
    protected var scope: CoroutineScope = createScope()
        private set

    private fun createScope() = CoroutineScope(coroutineDispatcher + SupervisorJob())

    protected val eventBus = BroadcastEventBus<ParticipantEvent>()
    val events = eventBus.readOnly()

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var participantInfo: LivekitModels.ParticipantInfo? by flowDelegate(null)
        private set

    /**
     * The participant's identity on the server. [name] should be preferred for UI usecases.
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var identity: Identity? by flowDelegate(identity)
        @VisibleForTesting set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var audioLevel: Float by flowDelegate(0f)
        @VisibleForTesting set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     *
     * A [ParticipantEvent.SpeakingChanged] event is emitted from [events] whenever
     * this changes.
     */
    @FlowObservable
    @get:FlowObservable
    var isSpeaking: Boolean by flowDelegate(false) { newValue, oldValue ->
        if (newValue != oldValue) {
            internalListener?.onSpeakingChanged(this)
            eventBus.postEvent(ParticipantEvent.SpeakingChanged(this, newValue), scope)
            if (newValue) {
                lastSpokeAt = Date().time
            }
        }
    }
        @VisibleForTesting set

    /**
     * The participant's name. To be used for user-facing purposes (i.e. when displayed in the UI).
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     *
     * A [ParticipantEvent.NameChanged] event is emitted from [events] whenever
     * this changes.
     */
    @FlowObservable
    @get:FlowObservable
    var name by flowDelegate<String?>(null) { newValue, oldValue ->
        if (newValue != oldValue) {
            eventBus.postEvent(ParticipantEvent.NameChanged(this, newValue), scope)
        }
    }
        @VisibleForTesting set

    /**
     * The metadata for this participant.
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     *
     * A [ParticipantEvent.MetadataChanged] event is emitted from [events] whenever
     * this changes.
     */
    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null) { newMetadata, oldMetadata ->
        if (newMetadata != oldMetadata) {
            internalListener?.onMetadataChanged(this, oldMetadata)
            eventBus.postEvent(ParticipantEvent.MetadataChanged(this, oldMetadata), scope)
        }
    }
        @VisibleForTesting set

    /**
     * The attributes set on this participant.
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     *
     * A [ParticipantEvent.AttributesChanged] event is emitted from [events] whenever
     * this changes.
     */
    @FlowObservable
    @get:FlowObservable
    var attributes: Map<String, String> by flowDelegate(emptyMap()) { newAttributes, oldAttributes ->
        if (newAttributes != oldAttributes) {
            val diff = diffMapChange(newAttributes, oldAttributes, "")

            if (diff.isNotEmpty()) {
                eventBus.postEvent(ParticipantEvent.AttributesChanged(this, diff, oldAttributes), scope)
            }
        }
    }
        @VisibleForTesting set

    /**
     * The permissions for this participant.
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     *
     * A [ParticipantEvent.ParticipantPermissionsChanged] event is emitted from [events] whenever
     * this changes.
     */
    @FlowObservable
    @get:FlowObservable
    var permissions: ParticipantPermission? by flowDelegate(null) { newPermissions, oldPermissions ->
        if (newPermissions != oldPermissions) {
            eventBus.postEvent(
                ParticipantEvent.ParticipantPermissionsChanged(
                    this,
                    newPermissions,
                    oldPermissions,
                ),
                scope,
            )
        }
    }
        internal set

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var connectionQuality by flowDelegate(ConnectionQuality.UNKNOWN)
        internal set

    /**
     * Timestamp when participant joined room, in milliseconds
     */
    val joinedAt
        get() = participantInfo?.joinedAt?.times(1000)

    /**
     * Timestamp when the participant last started speaking, in milliseconds
     */
    @FlowObservable
    @get:FlowObservable
    var lastSpokeAt by flowDelegate<Long?>(null)
        internal set

    /**
     * The kind of participant (i.e. a standard client participant, AI agent, etc.)
     */
    @FlowObservable
    @get:FlowObservable
    var kind by flowDelegate(Kind.UNKNOWN)
        internal set

    /**
     * @suppress
     */
    @Deprecated("Use events instead")
    @VisibleForTesting
    var internalListener: ParticipantListener? = null

    val hasInfo
        get() = participantInfo != null

    /**
     * Maps track sids to their track publications.
     *
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    var trackPublications by flowDelegate(emptyMap<String, TrackPublication>())
        protected set

    private fun Flow<Map<String, TrackPublication>>.trackUpdateFlow(): Flow<List<Pair<TrackPublication, Track?>>> {
        return flatMapLatest { videoTracks ->
            if (videoTracks.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    videoTracks.values
                        .map { trackPublication ->
                            // Re-emit when track changes
                            trackPublication::track.flow
                                .map { trackPublication to trackPublication.track }
                        },
                ) { trackPubs ->
                    trackPubs.toList()
                }
            }
        }
    }

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    val audioTrackPublications by flowDelegate(
        stateFlow = ::trackPublications.flow
            .map { it.filterValues { publication -> publication.kind == Track.Kind.AUDIO } }
            .trackUpdateFlow()
            .stateIn(delegateScope, SharingStarted.Eagerly, emptyList()),
    )

    /**
     * Changes can be observed by using [io.livekit.android.util.flow]
     */
    @FlowObservable
    @get:FlowObservable
    val videoTrackPublications by flowDelegate(
        stateFlow = ::trackPublications.flow
            .map { it.filterValues { publication -> publication.kind == Track.Kind.VIDEO } }
            .trackUpdateFlow()
            .stateIn(delegateScope, SharingStarted.Eagerly, emptyList()),
    )

    /**
     * @suppress
     */
    fun addTrackPublication(publication: TrackPublication) {
        val track = publication.track
        track?.sid = publication.sid
        trackPublications = trackPublications.toMutableMap().apply {
            this[publication.sid] = publication
        }
    }

    /**
     * Retrieves the first track that matches the source, or null
     */
    open fun getTrackPublication(source: Track.Source): TrackPublication? {
        if (source == Track.Source.UNKNOWN) {
            return null
        }

        for ((_, pub) in trackPublications) {
            if (pub.source == source) {
                return pub
            }

            // Alternative heuristics for finding track if source is unknown
            if (pub.source == Track.Source.UNKNOWN) {
                if (source == Track.Source.MICROPHONE && pub.kind == Track.Kind.AUDIO) {
                    return pub
                }
                if (source == Track.Source.CAMERA && pub.kind == Track.Kind.VIDEO && pub.name != "screen") {
                    return pub
                }
                if (source == Track.Source.SCREEN_SHARE && pub.kind == Track.Kind.VIDEO && pub.name == "screen") {
                    return pub
                }
            }
        }
        return null
    }

    /**
     * Retrieves the first track that matches [name], or null
     */
    open fun getTrackPublicationByName(name: String): TrackPublication? {
        for ((_, pub) in trackPublications) {
            if (pub.name == name) {
                return pub
            }
        }
        return null
    }

    fun isCameraEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.CAMERA)
        return isTrackPublicationEnabled(pub)
    }

    fun isMicrophoneEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.MICROPHONE)
        return isTrackPublicationEnabled(pub)
    }

    fun isScreenShareEnabled(): Boolean {
        val pub = getTrackPublication(Track.Source.SCREEN_SHARE)
        return isTrackPublicationEnabled(pub)
    }

    private fun isTrackPublicationEnabled(pub: TrackPublication?): Boolean {
        return !(pub?.muted ?: true)
    }

    /**
     * @suppress
     */
    open fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        sid = Sid(info.sid)
        identity = Identity(info.identity)
        participantInfo = info
        metadata = info.metadata
        name = info.name
        kind = Kind.fromProto(info.kind)
        if (info.hasPermission()) {
            permissions = ParticipantPermission.fromProto(info.permission)
        }
        attributes = info.attributesMap
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Participant

        if (sid != other.sid) return false

        return true
    }

    override fun hashCode(): Int {
        return sid.hashCode()
    }

    // Internal methods just for posting events.
    internal fun onTrackMuted(trackPublication: TrackPublication) {
        internalListener?.onTrackMuted(trackPublication, this)
        eventBus.postEvent(ParticipantEvent.TrackMuted(this, trackPublication), scope)
    }

    internal fun onTrackUnmuted(trackPublication: TrackPublication) {
        internalListener?.onTrackUnmuted(trackPublication, this)
        eventBus.postEvent(ParticipantEvent.TrackUnmuted(this, trackPublication), scope)
    }

    internal fun onTrackStreamStateChanged(trackEvent: TrackEvent.StreamStateChanged) {
        val trackPublication = trackPublications[trackEvent.track.sid] ?: return
        eventBus.postEvent(
            ParticipantEvent.TrackStreamStateChanged(this, trackPublication, trackEvent.streamState),
            scope,
        )
    }

    internal fun onTranscriptionReceived(transcription: RoomEvent.TranscriptionReceived) {
        if (transcription.participant != this) {
            return
        }
        eventBus.postEvent(
            ParticipantEvent.TranscriptionReceived(
                this,
                transcriptions = transcription.transcriptionSegments,
                publication = transcription.publication,
            ),
            scope,
        )
    }

    internal fun reinitialize() {
        if (!scope.isActive) {
            scope = createScope()
        }
    }

    /**
     * @suppress
     */
    @VisibleForTesting
    open fun dispose() {
        scope.cancel()

        sid = Sid("")
        name = null
        identity = null
        metadata = null
        participantInfo = null
        permissions = null
        connectionQuality = ConnectionQuality.UNKNOWN
    }

    enum class Kind {
        AGENT,
        STANDARD,
        INGRESS,
        EGRESS,
        SIP,
        UNKNOWN,
        ;

        companion object {
            /**
             * @suppress
             */
            fun fromProto(proto: LivekitModels.ParticipantInfo.Kind): Kind {
                return when (proto) {
                    LivekitModels.ParticipantInfo.Kind.AGENT -> AGENT
                    LivekitModels.ParticipantInfo.Kind.STANDARD -> STANDARD
                    LivekitModels.ParticipantInfo.Kind.INGRESS -> INGRESS
                    LivekitModels.ParticipantInfo.Kind.EGRESS -> EGRESS
                    LivekitModels.ParticipantInfo.Kind.SIP -> SIP
                    LivekitModels.ParticipantInfo.Kind.UNRECOGNIZED -> UNKNOWN
                }
            }
        }
    }
}

/**
 * @suppress
 */
@Deprecated("Use Participant.events instead.")
interface ParticipantListener {
    // all participants
    /**
     * When a participant's metadata is updated, fired for all participants
     */
    fun onMetadataChanged(participant: Participant, prevMetadata: String?) {}

    /**
     * Fired when the current participant's isSpeaking property changes. (including LocalParticipant)
     */
    fun onSpeakingChanged(participant: Participant) {}

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackMuted(publication: TrackPublication, participant: Participant) {}

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {}

    // local participants
    /**
     * When a new track is published by the local participant.
     */
    fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {}

    /**
     * A [LocalParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {}

    // remote participants
    /**
     * When a new track is published to room after the local participant has joined.
     *
     * It will not fire for tracks that are already published
     */
    fun onTrackPublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * A [RemoteParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * Subscribed to a new track
     */
    fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {}

    /**
     * Error had occurred while subscribing to a track
     */
    fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant,
    ) {
    }

    /**
     * A subscribed track is no longer available.
     * Clients should listen to this event and handle cleanup
     */
    fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
    ) {
    }

    /**
     * Received data published by another participant
     */
    fun onDataReceived(data: ByteArray, participant: RemoteParticipant) {}
}

enum class ConnectionQuality {
    EXCELLENT,
    GOOD,
    POOR,
    UNKNOWN,
    LOST,
    ;

    companion object {
        fun fromProto(proto: LivekitModels.ConnectionQuality): ConnectionQuality {
            return when (proto) {
                LivekitModels.ConnectionQuality.EXCELLENT -> EXCELLENT
                LivekitModels.ConnectionQuality.GOOD -> GOOD
                LivekitModels.ConnectionQuality.POOR -> POOR
                LivekitModels.ConnectionQuality.UNRECOGNIZED -> UNKNOWN
                LivekitModels.ConnectionQuality.LOST -> LOST
            }
        }
    }
}

data class ParticipantPermission(
    val canPublish: Boolean,
    val canSubscribe: Boolean,
    val canPublishData: Boolean,
    val hidden: Boolean,
    val recorder: Boolean,
    /**
     * The list of allowed sources. If this is empty, then all sources are allowed.
     */
    val canPublishSources: List<Track.Source>,
    val canUpdateMetadata: Boolean,
    val canSubscribeMetrics: Boolean,
) {
    companion object {
        fun fromProto(proto: LivekitModels.ParticipantPermission): ParticipantPermission {
            return ParticipantPermission(
                canPublish = proto.canPublish,
                canSubscribe = proto.canSubscribe,
                canPublishData = proto.canPublishData,
                hidden = proto.hidden,
                recorder = proto.recorder,
                canPublishSources = proto.canPublishSourcesList.map { Track.Source.fromProto(it) },
                canUpdateMetadata = proto.canUpdateMetadata,
                canSubscribeMetrics = proto.canSubscribeMetrics,
            )
        }
    }
}
