package io.livekit.android.room.participant

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.track.*
import io.livekit.android.util.CloseableCoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class RemoteParticipant(
    sid: String, name: String? = null
) : Participant(sid, name), RemoteDataTrack.Listener {
    /**
     * @suppress
     */
    constructor(info: LivekitModels.ParticipantInfo) : this(info.sid, info.identity) {
        updateFromInfo(info)
    }

    var listener: Listener? = null
        set(v) {
            field = v
            participantListener = v
        }

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob())

    fun getTrackPublication(sid: String): TrackPublication? =
        tracks[sid]

    /**
     * @suppress
     */
    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        val hadInfo = hasInfo
        super.updateFromInfo(info)

        val validTrackPublication = mutableMapOf<String, TrackPublication>()
        val newTrackPublications = mutableMapOf<String, TrackPublication>()

        for (trackInfo in info.tracksList) {
            val trackSid = trackInfo.sid
            var publication = getTrackPublication(trackSid)

            if (publication == null) {
                publication = TrackPublication(trackInfo)

                newTrackPublications[trackSid] = publication
                addTrackPublication(publication)
            } else {
                publication.updateFromInfo(trackInfo)
            }

            validTrackPublication[trackSid] = publication
        }

        if (hadInfo) {
            for (publication in newTrackPublications.values) {
                listener?.onPublish(publication, this)
            }
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
    fun addSubscribedMediaTrack(mediaTrack: MediaStreamTrack, sid: String, triesLeft: Int = 20) {
        val publication = getTrackPublication(sid)
        val track: Track = when (val kind = mediaTrack.kind()) {
            KIND_AUDIO -> RemoteAudioTrack(sid = sid, mediaTrack = mediaTrack as AudioTrack, name = "")
            KIND_VIDEO -> RemoteVideoTrack(sid = sid, mediaTrack = mediaTrack as VideoTrack, name = "")
            else -> throw TrackException.InvalidTrackTypeException("invalid track type: $kind")
        }

        if (publication == null) {
            if (triesLeft == 0) {
                val message = "Could not find published track with sid: $sid"
                val exception = TrackException.InvalidTrackStateException(message)
                Timber.e { "remote participant ${this.sid} --- $message" }

                listener?.onFailToSubscribe(sid, exception, this)
            } else {
                coroutineScope.launch {
                    delay(150)
                    addSubscribedMediaTrack(mediaTrack, sid, triesLeft - 1)
                }
            }
            return
        }

        val remoteTrack = track as RemoteTrack
        publication.track = track
        track.name = publication.name
        remoteTrack.sid = publication.sid

        // TODO: how does mediatrack send ended event?

        listener?.onSubscribe(track, publication, this)
    }

    /**
     * @suppress
     */
    fun addSubscribedDataTrack(dataChannel: DataChannel, sid: String, name: String) {
        val track = DataTrack(name, dataChannel)
        var publication = getTrackPublication(sid)

        if (publication != null) {
            publication.track = track
        } else {
            val trackInfo = LivekitModels.TrackInfo.newBuilder()
                .setSid(sid)
                .setName(name)
                .setType(LivekitModels.TrackType.DATA)
                .build()
            publication = TrackPublication(info = trackInfo, track = track)
            addTrackPublication(publication)
            if (hasInfo) {
                listener?.onPublish(publication, this)
            }
        }

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val newState = dataChannel.state()
                if (newState == DataChannel.State.CLOSED) {
                    publication.track = null
                    listener?.onUnsubscribe(track, publication, this@RemoteParticipant)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                listener?.onReceive(buffer.data, track, this@RemoteParticipant)
            }
        })
        listener?.onSubscribe(track, publication, participant = this)
    }

    fun unpublishTrack(trackSid: String, sendUnpublish: Boolean = false) {
        val publication = tracks.remove(trackSid) ?: return
        when (publication.kind) {
            LivekitModels.TrackType.AUDIO -> audioTracks.remove(trackSid)
            LivekitModels.TrackType.VIDEO -> videoTracks.remove(trackSid)
            LivekitModels.TrackType.DATA -> dataTracks.remove(trackSid)
            else -> throw TrackException.InvalidTrackTypeException()
        }

        val track = publication.track
        if (track != null) {
            track.stop()
            listener?.onUnsubscribe(track, publication, this)
        }
        if (sendUnpublish) {
            listener?.onUnpublish(publication, this)
        }
    }

    override fun onReceiveString(message: String, dataTrack: DataTrack) {
        TODO("Not yet implemented")
    }

    override fun onReceiveData(message: DataChannel.Buffer, dataTrack: DataTrack) {
        TODO("Not yet implemented")
    }

    companion object {
        private const val KIND_AUDIO = "audio"
        private const val KIND_VIDEO = "video"
    }

    interface Listener: Participant.Listener {
        fun onPublish(publication: TrackPublication, participant: RemoteParticipant) {}
        fun onUnpublish(publication: TrackPublication, participant: RemoteParticipant) {}

        fun onEnable(publication: TrackPublication, participant: RemoteParticipant) {}
        fun onDisable(publication: TrackPublication, participant: RemoteParticipant) {}

        fun onSubscribe(track: Track, publication: TrackPublication, participant: RemoteParticipant) {}
        fun onFailToSubscribe(
            sid: String,
            exception: Exception,
            participant: RemoteParticipant
        ) {
        }

        fun onUnsubscribe(
            track: Track,
            publications: TrackPublication,
            participant: RemoteParticipant
        ) {
        }

        fun onReceive(
            data: ByteBuffer,
            dataTrack: DataTrack,
            participant: RemoteParticipant
        ) {
        }

        fun switchedOffVideo(track: RemoteVideoTrack, participant: RemoteParticipant) {}
        fun switchedOnVideo(track: RemoteVideoTrack, participant: RemoteParticipant) {}
    }

}