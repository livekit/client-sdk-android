package io.livekit.android.room.participant

import android.content.Context
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import io.livekit.android.util.LKLog
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import java.nio.ByteBuffer

class LocalParticipant
@AssistedInject
internal constructor(
    @Assisted
    info: LivekitModels.ParticipantInfo,
    internal val engine: RTCEngine,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
) :
    Participant(info.sid, info.identity) {

    init {
        updateFromInfo(info)
    }

    private val localTrackPublications
        get() = tracks.values.toList()

    fun createAudioTrack(
        name: String = "",
        options: LocalAudioTrackOptions = LocalAudioTrackOptions(),
    ): LocalAudioTrack {
        val audioConstraints = MediaConstraints()
        val items = listOf(
            MediaConstraints.KeyValuePair("googEchoCancellation", options.echoCancellation.toString()),
            MediaConstraints.KeyValuePair("googAutoGainControl", options.autoGainControl.toString()),
            MediaConstraints.KeyValuePair("googHighpassFilter", options.highPassFilter.toString()),
            MediaConstraints.KeyValuePair("googNoiseSuppression", options.noiseSuppression.toString()),
            MediaConstraints.KeyValuePair("googTypingNoiseDetection", options.typingNoiseDetection.toString()),
        )
        audioConstraints.optional.addAll(items)
        return LocalAudioTrack.createTrack(peerConnectionFactory, audioConstraints, name)
    }

    fun createVideoTrack(
        name: String = "",
        options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
    ): LocalVideoTrack {
        return LocalVideoTrack.createTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase
        )
    }

    suspend fun publishAudioTrack(
        track: LocalAudioTrack,
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, kind = track.kind.toProto())
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: sendEncodings to customize
        val transceiver = engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)
        track.transceiver = transceiver

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, kind = LivekitModels.TrackType.VIDEO, dimensions = track.dimensions)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: video encodings & simulcast
        val transceiver = engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)
        track.transceiver = transceiver

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    fun unpublishTrack(track: Track) {
        val publication = localTrackPublications.firstOrNull { it.track == track }
        if (publication === null) {
            LKLog.d { "this track was never published." }
            return
        }
        val sid = publication.sid
        tracks.remove(sid)
        when (publication.kind) {
            Track.Kind.AUDIO -> audioTracks.remove(sid)
            Track.Kind.VIDEO -> videoTracks.remove(sid)
            else -> {}
        }
        val senders = engine.publisher.peerConnection.senders ?: return
        for (sender in senders) {
            val t = sender.track() ?: continue
            if (t.id() == track.rtcTrack.id()) {
                engine.publisher.peerConnection.removeTrack(sender)
            }
        }
        track.stop()
    }

    /**
     * Publish a new data payload to the room. Data will be forwarded to each participant in the room.
     * Each payload must not exceed 15k in size
     *
     * @param data payload to send
     * @param reliability for delivery guarantee, use RELIABLE. for fastest delivery without guarantee, use LOSSY
     * @param destination list of participant SIDs to deliver the payload, null to deliver to everyone
     */
    @Suppress("unused")
    suspend fun publishData(data: ByteArray, reliability: DataPublishReliability, destination: List<String>?) {
        if (data.size > RTCEngine.MAX_DATA_PACKET_SIZE) {
            throw IllegalArgumentException("cannot publish data larger than " + RTCEngine.MAX_DATA_PACKET_SIZE)
        }

        val kind = when (reliability) {
            DataPublishReliability.RELIABLE -> LivekitModels.DataPacket.Kind.RELIABLE
            DataPublishReliability.LOSSY -> LivekitModels.DataPacket.Kind.LOSSY
        }
        val packetBuilder = LivekitModels.UserPacket.newBuilder().
                setPayload(ByteString.copyFrom(data)).
                setParticipantSid(sid)
        if (destination != null) {
            packetBuilder.addAllDestinationSids(destination)
        }
        val dataPacket = LivekitModels.DataPacket.newBuilder().
            setUser(packetBuilder).
            setKind(kind).
            build()

        engine.sendData(dataPacket)
    }

    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        super.updateFromInfo(info)

        // detect tracks that have been muted on the server side, apply those changes
        for (ti in info.tracksList) {
            val publication = this.tracks[ti.sid] as? LocalTrackPublication ?: continue
            if (ti.muted != publication.muted) {
                publication.setMuted(ti.muted)
            }
        }
    }

    fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        val pub = tracks[trackSid]
        pub?.muted = muted
    }

    interface PublishListener {
        fun onPublishSuccess(publication: TrackPublication) {}
        fun onPublishFailure(exception: Exception) {}
    }

    @AssistedFactory
    interface Factory {
        fun create(info: LivekitModels.ParticipantInfo): LocalParticipant
    }
}
