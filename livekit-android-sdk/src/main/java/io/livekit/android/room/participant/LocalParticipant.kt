package io.livekit.android.room.participant

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import livekit.LivekitModels
import org.webrtc.DataChannel
import org.webrtc.RtpTransceiver
import java.util.*

class LocalParticipant(info: LivekitModels.ParticipantInfo, private val engine: RTCEngine) :
    Participant(info.sid, info.identity) {

    init {
        updateFromInfo(info)
    }

    val localTrackPublications
        get() = tracks.values.toList()

    var listener: Listener? = null
        set(v) {
            field = v
            participantListener = v
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
            engine.addTrack(cid = cid, name = track.name, kind = track.kind)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: sendEncodings to customize
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = TrackPublication(trackInfo, track)
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
            engine.addTrack(cid = cid, name = track.name, kind = LivekitModels.TrackType.VIDEO)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid)
        )
        // TODO: video encodings & simulcast
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = TrackPublication(trackInfo, track)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    suspend fun publishDataTrack(
        track: LocalDataTrack,
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.cid
        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, track.kind)
        val publication = TrackPublication(trackInfo, track)

        val config = DataChannel.Init().apply {
            ordered = track.options.ordered
            maxRetransmitTimeMs = track.options.maxRetransmitTimeMs
            maxRetransmits = track.options.maxRetransmits
        }

        val dataChannel = engine.publisher.peerConnection.createDataChannel(track.name, config)
        if (dataChannel == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("could not create data channel"))
            return
        }
        track.dataChannel = dataChannel
        track.updateConfig(config)
        addTrackPublication(publication)

        publishListener?.onPublishSuccess(publication)
    }

    fun unpublishTrack(track: Track) {
        val publication = localTrackPublications.firstOrNull { it.track == track }
        if (publication === null) {
            Timber.d { "this track was never published." }
            return
        }
        track.stop()
        if (track is MediaTrack) {
            unpublishMediaTrack(track, sid)
        }
        val sid = publication.sid
        tracks.remove(sid)
        when (publication.kind) {
            LivekitModels.TrackType.AUDIO -> audioTracks.remove(sid)
            LivekitModels.TrackType.VIDEO -> videoTracks.remove(sid)
            LivekitModels.TrackType.DATA -> dataTracks.remove(sid)
        }
    }

    private fun <T> unpublishMediaTrack(
        track: T,
        sid: String
    ) where T : MediaTrack {
        val senders = engine?.publisher?.peerConnection?.senders ?: return
        for (sender in senders) {
            val t = sender.track() ?: continue
            if (t == track.rtcTrack) {
                engine?.publisher?.peerConnection?.removeTrack(sender)
            }
        }
    }

    interface PublishListener {
        fun onPublishSuccess(publication: TrackPublication) {}
        fun onPublishFailure(exception: Exception) {}
    }
}
