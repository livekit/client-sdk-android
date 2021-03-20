package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.vdurmont.semver4j.Semver
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.room.util.unpackedTrackLabel
import livekit.Model
import livekit.Rtc
import org.webrtc.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Room
@AssistedInject
constructor(
    @Assisted private val connectOptions: ConnectOptions,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
) : RTCEngine.Listener {
    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING;
    }

    inline class Sid(val sid: String)

    var listener: Listener? = null

    var sid: Sid? = null
        private set
    var name: String? = null
        private set
    var state: State = State.DISCONNECTED
        private set
    var localParticipant: LocalParticipant? = null
        private set
    private val mutableRemoteParticipants = mutableMapOf<Participant.Sid, RemoteParticipant>()
    val remoteParticipants: Map<Participant.Sid, RemoteParticipant>
        get() = mutableRemoteParticipants

    private val mutableActiveSpeakers = mutableListOf<Participant>()
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var connectContinuation: Continuation<Unit>? = null
    suspend fun connect(url: String, token: String, isSecure: Boolean) {
        if (localParticipant != null) {
            Timber.d { "Attempting to connect to room when already connected." }
            return
        }
        engine.join(url, token, isSecure)

        return suspendCoroutine { connectContinuation = it }
    }

    fun disconnect() {
        engine.close()
        state = State.DISCONNECTED
        listener?.onDisconnect(this, null)
    }

    private fun handleParticipantDisconnect(sid: Participant.Sid, participant: RemoteParticipant) {
        val removedParticipant = mutableRemoteParticipants.remove(sid) ?: return
        removedParticipant.tracks.values.forEach { publication ->
            removedParticipant.unpublishTrack(publication.trackSid)
        }

        listener?.onParticipantDisconnected(this, removedParticipant)
    }

    private fun getOrCreateRemoteParticipant(
        sid: Participant.Sid,
        info: Model.ParticipantInfo? = null
    ): RemoteParticipant {
        var participant = remoteParticipants[sid]
        if (participant != null) {
            return participant
        }

        participant = if (info != null) {
            RemoteParticipant(info)
        } else {
            RemoteParticipant(sid, null)
        }
        mutableRemoteParticipants[sid] = participant
        return participant
    }

    private fun handleSpeakerUpdate(speakerInfos: List<Rtc.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<Participant.Sid>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = Participant.Sid(speakerInfo.sid)
            seenSids.add(speakerSid)

            if (speakerSid == localParticipant?.sid) {
                localParticipant.audioLevel = speakerInfo.level
                speakers.add(localParticipant)
            } else {
                val participant = remoteParticipants[speakerSid]
                if (participant != null) {
                    participant.audioLevel = speakerInfo.level
                    speakers.add(participant)
                }
            }
        }

        if (localParticipant != null && seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach { it.audioLevel = 0.0f }

        mutableActiveSpeakers.clear()
        mutableActiveSpeakers.addAll(speakers)
        listener?.onActiveSpeakersChanged(speakers, this)
    }

    @AssistedFactory
    interface Factory {
        fun create(connectOptions: ConnectOptions): Room
    }

    interface Listener {
        fun onConnect(room: Room) {}
        fun onDisconnect(room: Room, error: Exception?) {}
        fun onParticipantConnected(room: Room, participant: RemoteParticipant) {}
        fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}
        fun onFailedToConnect(room: Room, error: Exception) {}
        fun onReconnecting(room: Room, error: Exception) {}
        fun onReconnect(room: Room) {}
        fun onStartRecording(room: Room) {}
        fun onStopRecording(room: Room) {}
        fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {}
    }

    override fun onJoin(response: Rtc.JoinResponse) {
        Timber.v { "engine did join, version: ${response.serverVersion}" }

        try {
            val serverVersion = Semver(response.serverVersion)
            if (serverVersion.major == 0 && serverVersion.minor < 5) {
                Timber.e { "This version of livekit requires server version >= 0.5.x" }
                return
            }
        } catch (e: Exception) {
            Timber.e { "Unable to parse server version!" }
            return
        }
        state = State.CONNECTED
        sid = Sid(response.room.sid)
        name = response.room.name

        if (response.hasParticipant()) {
            localParticipant = LocalParticipant(response.participant, engine)
        }
        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach {
                getOrCreateRemoteParticipant(Participant.Sid(it.sid), it)
            }
        }

        connectContinuation?.resume(Unit)
        connectContinuation = null
        listener?.onConnect(this)
    }

    override fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            Timber.i { "add track with empty streams?" }
            return
        }

        val participantSid = Participant.Sid(streams.first().id)
        val trackSid = Track.Sid(track.id())
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedMediaTrack(track, trackSid)
    }

    override fun onAddDataChannel(channel: DataChannel) {
        val unpackedTrackLabel = channel.unpackedTrackLabel()
        val (participantSid, trackSid, name) = unpackedTrackLabel
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedDataTrack(channel, trackSid, name)
    }

    override fun onPublishLocalTrack(cid: Track.Cid, track: Model.TrackInfo) {
    }


    override fun onUpdateParticipants(updates: List<Model.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = Participant.Sid(info.sid)

            if(localParticipant?.sid == participantSid) {
                localParticipant?.updateFromInfo(info)
            }

            val isNewParticipant = remoteParticipants.contains(participantSid)
            val participant = getOrCreateRemoteParticipant(participantSid, info)

            if (info.state == Model.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantSid, participant)
            } else if (isNewParticipant) {
                listener?.onParticipantConnected(this, participant)
            } else {
                participant.updateFromInfo(info)
            }
        }
    }

    override fun onUpdateSpeakers(speakers: List<Rtc.SpeakerInfo>) {
        handleSpeakerUpdate(speakers)
    }

    override fun onDisconnect(reason: String) {
        Timber.v { "engine did disconnect: $reason" }
        listener?.onDisconnect(this, null)
    }

    override fun onFailToConnect(error: Exception) {
        listener?.onFailedToConnect(this, error)
    }

    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */);
    }
}