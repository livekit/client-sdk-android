package io.livekit.android.room.participant

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import livekit.LivekitModels
import org.webrtc.DataChannel
import org.webrtc.RtpTransceiver
import java.util.*

class LocalParticipant(sid: Sid, name: String? = null) :
    Participant(sid, name) {

    /**
     * @suppress
     */
    constructor(info: LivekitModels.ParticipantInfo, engine: RTCEngine) : this(
        Sid(info.sid),
        info.identity
    ) {
        metadata = info.metadata
        this.engine = engine
    }

    val localAudioTrackPublications
        get() = audioTracks.values.toList()
    val localVideoTrackPublications
        get() = videoTracks.values.toList()
    val localDataTrackPublications
        get() = dataTracks.values.toList()

    var engine: RTCEngine? = null
    val listener: Listener? = null

    /**
     * @suppress
     */
    fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        sid = Sid(info.sid)
        name = info.identity
        metadata = info.metadata
    }

    suspend fun publishAudioTrack(
        track: LocalAudioTrack,
        options: LocalTrackPublicationOptions? = null
    ) {
        if (localAudioTrackPublications.any { it.track == track }) {
            listener?.onFailToPublishAudioTrack(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val engine = this.engine ?: run {
            listener?.onFailToPublishAudioTrack(IllegalStateException("engine is null!"))
            return
        }

        val trackInfo =
            engine.addTrack(cid = Track.Cid(cid), name = track.name, kind = LivekitModels.TrackType.AUDIO)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid.sid)
        )
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            listener?.onFailToPublishAudioTrack(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalAudioTrackPublication(trackInfo)
        val trackSid = Track.Sid(trackInfo.sid)
        track.sid = trackSid
        audioTracks[trackSid] = publication
        listener?.onPublishAudioTrack(track)
    }

    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        options: LocalTrackPublicationOptions? = null
    ) {

        if (localVideoTrackPublications.any { it.track == track }) {
            listener?.onFailToPublishVideoTrack(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val engine = this.engine ?: run {
            listener?.onFailToPublishVideoTrack(IllegalStateException("engine is null!"))
            return
        }

        val trackInfo =
            engine.addTrack(cid = Track.Cid(cid), name = track.name, kind = LivekitModels.TrackType.VIDEO)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid.sid)
        )
        val transceiver =
            engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        if (transceiver == null) {
            listener?.onFailToPublishVideoTrack(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        val publication = LocalVideoTrackPublication(trackInfo)
        val trackSid = Track.Sid(trackInfo.sid)
        track.sid = trackSid
        videoTracks[trackSid] = publication
        listener?.onPublishVideoTrack(track)
    }

    suspend fun publishDataTrack(
        track: LocalDataTrack,
        options: LocalTrackPublicationOptions? = null
    ) {

        if (localDataTrackPublications.any { it.track == track }) {
            listener?.onFailToPublishDataTrack(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.cid
        val engine = this.engine ?: run {
            listener?.onFailToPublishDataTrack(IllegalStateException("engine is null!"))
            return
        }

        val trackInfo =
            engine.addTrack(cid = cid, name = track.name, kind = LivekitModels.TrackType.DATA)
        val publication = LocalDataTrackPublication(trackInfo, track)
        val trackSid = Track.Sid(trackInfo.sid)
        track.sid = trackSid

        val config = DataChannel.Init().apply {
            ordered = track.options.ordered
            maxRetransmitTimeMs = track.options.maxRetransmitTimeMs
            maxRetransmits = track.options.maxRetransmits
        }

        val dataChannel = engine.publisher.peerConnection.createDataChannel(track.name, config)
        if (dataChannel != null) {
            track.rtcTrack = dataChannel
            track.updateConfig(config)
            dataTracks[trackSid] = publication
            listener?.onPublishDataTrack(track)
        } else {
            Timber.d { "error creating data channel with name: $name" }
            unpublishDataTrack(track)
        }
    }

    fun unpublishAudioTrack(track: LocalAudioTrack) {
        val sid = track.sid ?: run {
            Timber.d { "this track was never published." }
            return
        }
        unpublishMediaTrack(track, sid, audioTracks)
    }

    fun unpublishVideoTrack(track: LocalVideoTrack) {
        val sid = track.sid ?: run {
            Timber.d { "this track was never published." }
            return
        }
        unpublishMediaTrack(track, sid, audioTracks)
    }

    fun unpublishDataTrack(track: LocalDataTrack) {
        val sid = track.sid ?: run {
            Timber.d { "this track was never published." }
            return
        }

        val publication = dataTracks.remove(sid) as? LocalDataTrackPublication
        if (publication == null) {
            Timber.d { "track was not published with sid: $sid" }
            return
        }
        publication.dataTrack?.rtcTrack?.dispose()
    }

    private fun <T> unpublishMediaTrack(
        track: T,
        sid: Track.Sid,
        publications: MutableMap<Track.Sid, TrackPublication>
    ) where T : Track, T : MediaTrack {
        val removed = publications.remove(sid)
        if (removed != null) {
            Timber.d { "track was not published with sid: $sid" }
            return
        }

        track.mediaTrack.setEnabled(false)
        val senders = engine?.publisher?.peerConnection?.senders ?: return
        for (sender in senders) {
            val t = sender.track() ?: continue
            if (t == track.mediaTrack) {
                engine?.publisher?.peerConnection?.removeTrack(sender)
            }
        }
    }

    interface Listener {
        fun onPublishAudioTrack(track: LocalAudioTrack)
        fun onFailToPublishAudioTrack(exception: Exception)
        fun onPublishVideoTrack(track: LocalVideoTrack)
        fun onFailToPublishVideoTrack(exception: Exception)
        fun onPublishDataTrack(track: LocalDataTrack)
        fun onFailToPublishDataTrack(exception: Exception)
        //fun onNetworkQualityLevelChange
    }
}
