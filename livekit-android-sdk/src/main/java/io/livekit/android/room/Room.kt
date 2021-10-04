package io.livekit.android.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.github.ajalt.timberkt.Timber
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.Version
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.ParticipantListener
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.*
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Room
@AssistedInject
constructor(
    @Assisted private val context: Context,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
    private val localParticipantFactory: LocalParticipant.Factory
) : RTCEngine.Listener, ParticipantListener, ConnectivityManager.NetworkCallback() {
    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING;
    }

    @JvmInline
    value class Sid(val sid: String)

    var listener: RoomListener? = null

    var sid: Sid? = null
        private set
    var name: String? = null
        private set
    var state: State = State.DISCONNECTED
        private set
    lateinit var localParticipant: LocalParticipant
        private set
    private val mutableRemoteParticipants = mutableMapOf<String, RemoteParticipant>()
    val remoteParticipants: Map<String, RemoteParticipant>
        get() = mutableRemoteParticipants

    private val mutableActiveSpeakers = mutableListOf<Participant>()
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var hasLostConnectivity: Boolean = false
    suspend fun connect(url: String, token: String, options: ConnectOptions?) {
        state = State.CONNECTING
        val response = engine.join(url, token, options)
        Timber.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }

        sid = Sid(response.room.sid)
        name = response.room.name

        if (!response.hasParticipant()) {
            listener?.onFailedToConnect(this, RoomException.ConnectException("server didn't return any participants"))
            return
        }

        val lp = localParticipantFactory.create(response.participant)
        lp.listener = this
        localParticipant = lp
        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach {
                getOrCreateRemoteParticipant(it.sid, it)
            }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(networkRequest, this)
    }

    fun disconnect() {
        engine.client.sendLeave()
        handleDisconnect()
    }

    private fun handleParticipantDisconnect(sid: String) {
        val removedParticipant = mutableRemoteParticipants.remove(sid) ?: return
        removedParticipant.tracks.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid)
        }

        listener?.onParticipantDisconnected(this, removedParticipant)
    }

    @Synchronized
    private fun getOrCreateRemoteParticipant(
        sid: String,
        info: LivekitModels.ParticipantInfo? = null
    ): RemoteParticipant {
        var participant = remoteParticipants[sid]
        if (participant != null) {
            return participant
        }

        participant = if (info != null) {
            RemoteParticipant(engine.client, info)
        } else {
            RemoteParticipant(engine.client, sid, null)
        }
        participant.internalListener = this
        mutableRemoteParticipants[sid] = participant
        return participant
    }

    private fun handleActiveSpeakersUpdate(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<String>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = speakerInfo.sid!!
            seenSids.add(speakerSid)

            if (speakerSid == localParticipant.sid) {
                localParticipant.audioLevel = speakerInfo.level
                localParticipant.isSpeaking = true
                speakers.add(localParticipant)
            } else {
                val participant = remoteParticipants[speakerSid]
                if (participant != null) {
                    participant.audioLevel = speakerInfo.level
                    participant.isSpeaking = true
                    speakers.add(participant)
                }
            }
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
            localParticipant.isSpeaking = false
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach {
                it.audioLevel = 0.0f
                it.isSpeaking = false
            }

        mutableActiveSpeakers.clear()
        mutableActiveSpeakers.addAll(speakers)
        listener?.onActiveSpeakersChanged(speakers, this)
    }

    private fun handleSpeakersChanged(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val updatedSpeakers = mutableMapOf<String, Participant>()
        activeSpeakers.forEach {
            updatedSpeakers[it.sid] = it
        }

        speakerInfos.forEach { speaker ->
            val participant = if(speaker.sid == localParticipant.sid) {
                localParticipant
            } else {
                remoteParticipants[speaker.sid]
            } ?: return@forEach

            participant.audioLevel = speaker.level
            participant.isSpeaking = speaker.active

            if(speaker.active) {
                updatedSpeakers[speaker.sid] = participant
            } else {
                updatedSpeakers.remove(speaker.sid)
            }
        }

        val updatedSpeakersList = updatedSpeakers.values.toList()
            .sortedBy { it.audioLevel }

        mutableActiveSpeakers.clear()
        mutableActiveSpeakers.addAll(updatedSpeakersList)
        listener?.onActiveSpeakersChanged(updatedSpeakersList, this)
    }

    private fun reconnect() {
        if (state == State.RECONNECTING) {
            return
        }
        state = State.RECONNECTING
        engine.reconnect()
        listener?.onReconnecting(this)
    }

    private fun handleDisconnect() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(this)
        for (pub in localParticipant.tracks.values) {
            pub.track?.stop()
        }
        // stop remote tracks too
        for (p in remoteParticipants.values) {
            for (pub in p.tracks.values) {
                pub.track?.stop()
            }
        }
        engine.close()
        state = State.DISCONNECTED
        listener?.onDisconnect(this, null)
        listener = null
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(context: Context): Room
    }

    //------------------------------------- NetworkCallback -------------------------------------//

    /**
     * @suppress
     */
    override fun onLost(network: Network) {
        // lost connection, flip to reconnecting
        hasLostConnectivity = true
    }

    /**
     * @suppress
     */
    override fun onAvailable(network: Network) {
        // only actually reconnect after connection is re-established
        if (!hasLostConnectivity) {
            return
        }
        Timber.i { "network connection available, reconnecting" }
        reconnect()
        hasLostConnectivity = false
    }


    //----------------------------------- RTCEngine.Listener ------------------------------------//
    override fun onIceConnected() {
        state = State.CONNECTED
    }

    override fun onIceReconnected() {
        state = State.CONNECTED
        listener?.onReconnected(this)
    }

    /**
     * @suppress
     */
    override fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            Timber.i { "add track with empty streams?" }
            return
        }

        var (participantSid, trackSid) = unpackStreamId(streams.first().id)
        if (trackSid == null) {
            trackSid = track.id()
        }
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedMediaTrack(track, trackSid!!)
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = info.sid

            if(localParticipant.sid == participantSid) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = !remoteParticipants.contains(participantSid)
            val participant = getOrCreateRemoteParticipant(participantSid, info)

            if (info.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantSid)
            } else if (isNewParticipant) {
                listener?.onParticipantConnected(this, participant)
            } else {
                participant.updateFromInfo(info)
            }
        }
    }

    /**
     * @suppress
     */
    override fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>) {
        handleActiveSpeakersUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        handleSpeakersChanged(speakers)
    }

    /**
     * @suppress
     */
    override fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind) {
        val participant = remoteParticipants[packet.participantSid] ?: return
        val data = packet.payload.toByteArray()

        listener?.onDataReceived(data, participant, this)
        participant.listener?.onDataReceived(data, participant)
    }

    /**
     * @suppress
     */
    override fun onDisconnect(reason: String) {
        Timber.v { "engine did disconnect: $reason" }
        handleDisconnect()
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Exception) {
        listener?.onFailedToConnect(this, error)
    }

    //------------------------------- RemoteParticipant.Listener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
        listener?.onMetadataChanged(participant, prevMetadata, this)
    }

    /** @suppress */
    override fun onTrackMuted(publication: TrackPublication, participant: Participant) {
        listener?.onTrackMuted(publication, participant, this)
    }

    /** @suppress */
    override fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {
        listener?.onTrackUnmuted(publication, participant, this)
    }

    /**
     * @suppress
     */
    override fun onTrackPublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackPublished(publication,  participant, this)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackUnpublished(publication,  participant, this)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {
        listener?.onTrackSubscribed(track, publication, participant, this)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant
    ) {
        listener?.onTrackSubscriptionFailed(sid, exception, participant, this)
    }

    /**
     * @suppress
     */
    override fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant
    ) {
        listener?.onTrackUnsubscribed(track, publication, participant, this)
    }

    /**
     * @suppress
     * // TODO(@dl): can this be moved out of Room/SDK?
     */
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */)
    }

    /**
     * @suppress
     * // TODO(@dl): can this be moved out of Room/SDK?
     */
    fun initVideoRenderer(viewRenderer: TextureViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */)
    }

}

/**
 * Room Listener, this class provides callbacks that clients should override.
 *
 */
interface RoomListener {
    /**
     * A network change has been detected and LiveKit attempts to reconnect to the room
     * When reconnect attempts succeed, the room state will be kept, including tracks that are subscribed/published
     */
    fun onReconnecting(room: Room) {}

    /**
     * The reconnect attempt had been successful
     */
    fun onReconnected(room: Room) {}

    /**
     * Disconnected from room
     */
    fun onDisconnect(room: Room, error: Exception?) {}

    /**
     * When a [RemoteParticipant] joins after the local participant. It will not emit events
     * for participants that are already in the room
     */
    fun onParticipantConnected(room: Room, participant: RemoteParticipant) {}

    /**
     * When a [RemoteParticipant] leaves after the local participant has joined.
     */
    fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}

    /**
     * Could not connect to the room
     */
    fun onFailedToConnect(room: Room, error: Exception) {}
//        fun onReconnecting(room: Room, error: Exception) {}
//        fun onReconnect(room: Room) {}

    /**
     * Active speakers changed. List of speakers are ordered by their audio level. loudest
     * speakers first. This will include the [LocalParticipant] too.
     */
    fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {}

    // Participant callbacks
    /**
     * Participant metadata is a simple way for app-specific state to be pushed to all users.
     * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
     * this event will be fired for all clients in the room.
     */
    fun onMetadataChanged(participant: Participant, prevMetadata: String?, room: Room) {}

    /**
     * The participant was muted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackMuted(publication: TrackPublication, participant: Participant, room: Room) {}

    /**
     * The participant was unmuted.
     *
     * For the local participant, the callback will be called if setMute was called on the
     * [LocalTrackPublication], or if the server has requested the participant to be muted
     */
    fun onTrackUnmuted(publication: TrackPublication, participant: Participant, room: Room) {}

    /**
     * When a new track is published to room after the local participant has joined. It will
     * not fire for tracks that are already published
     */
    fun onTrackPublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * A [RemoteParticipant] has unpublished a track
     */
    fun onTrackUnpublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * The [LocalParticipant] has subscribed to a new track. This event will always fire as
     * long as new tracks are ready for use.
     */
    fun onTrackSubscribed(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * Could not subscribe to a track
     */
    fun onTrackSubscriptionFailed(sid: String, exception: Exception, participant: RemoteParticipant, room: Room) {}

    /**
     * A subscribed track is no longer available. Clients should listen to this event and ensure
     * the track removes all renderers
     */
    fun onTrackUnsubscribed(track: Track, publications: TrackPublication, participant: RemoteParticipant, room: Room) {}

    /**
     * Received data published by another participant
     */
    fun onDataReceived(data: ByteArray, participant: RemoteParticipant, room: Room) {}
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}

internal fun unpackStreamId(packed: String): Pair<String, String?> {
    val parts = packed.split('|')
    if (parts.size != 2) {
        return Pair(packed, null)
    }
    return Pair(parts[0], parts[1])
}