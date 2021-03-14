package io.livekit.android.room.participant

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.track.*
import io.livekit.android.util.CloseableCoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.Model
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

class RemoteParticipant(
    sid: Sid, name: String? = null
) : Participant(sid, name), RemoteDataTrack.Listener {

    constructor(info: Model.ParticipantInfo) : this(Sid(info.sid), info.identity) {
        updateFromInfo(info)
    }

    val remoteAudioTracks
        get() = audioTracks.values.toList()
    val remoteVideoTracks
        get() = videoTracks.values.toList()
    val remoteDataTracks
        get() = dataTracks.values.toList()

    val listener: Listener? = null

    var participantInfo: Model.ParticipantInfo? = null

    val hasInfo
        get() = participantInfo != null

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob())

    fun getTrackPublication(sid: Track.Sid): RemoteTrackPublication? =
        tracks[sid] as? RemoteTrackPublication

    fun updateFromInfo(info: Model.ParticipantInfo) {
        val hadInfo = hasInfo
        sid = Sid(info.sid)
        name = info.identity
        participantInfo = info
        metadata = info.metadata

        val validTrackPublication = mutableMapOf<Track.Sid, RemoteTrackPublication>()
        val newTrackPublications = mutableMapOf<Track.Sid, RemoteTrackPublication>()

        for (trackInfo in info.tracksList) {
            val trackSid = Track.Sid(trackInfo.sid)
            var publication = getTrackPublication(trackSid)

            if (publication == null) {
                publication = when (trackInfo.type) {
                    Model.TrackType.AUDIO -> RemoteAudioTrackPublication(trackInfo)
                    Model.TrackType.VIDEO -> RemoteVideoTrackPublication(trackInfo)
                    Model.TrackType.DATA -> RemoteDataTrackPublication(trackInfo)
                    Model.TrackType.UNRECOGNIZED -> throw TrackException.InvalidTrackTypeException()
                    null -> throw NullPointerException("trackInfo.type is null")
                }

                newTrackPublications[trackSid] = publication
                addTrack(publication)
            } else {
                publication.updateFromInfo(trackInfo)
            }

            validTrackPublication[trackSid] = publication
        }

        if (hadInfo) {
            for (publication in newTrackPublications.values) {
                sendTrackPublishedEvent(publication)
            }
        }

        val invalidKeys = tracks.keys - validTrackPublication.keys
        for (invalidKey in invalidKeys) {
            val publication = tracks[invalidKey] ?: continue
            unpublishTrack(publication.trackSid, true)
        }
    }

    fun addSubscribedMediaTrack(rtcTrack: MediaStreamTrack, sid: Track.Sid, triesLeft: Int = 20) {
        val publication = getTrackPublication(sid)
        val track: Track = when (val kind = rtcTrack.kind()) {
            KIND_AUDIO -> RemoteAudioTrack(sid = sid, rtcTrack = rtcTrack as AudioTrack, name = "")
            KIND_VIDEO -> RemoteVideoTrack(sid = sid, rtcTrack = rtcTrack as VideoTrack, name = "")
            else -> throw TrackException.InvalidTrackTypeException("invalid track type: $kind")
        }

        if (publication == null) {
            if (triesLeft == 0) {
                val message = "Could not find published track with sid: $sid"
                val exception = TrackException.InvalidTrackStateException(message)
                Timber.e { "remote participant ${this.sid} --- $message" }
                when (rtcTrack.kind()) {
                    KIND_AUDIO -> {
                        listener?.onFailToSubscribe(
                            audioTrack = track as RemoteAudioTrack,
                            exception = exception,
                            participant = this
                        )
                    }

                    KIND_VIDEO -> {
                        listener?.onFailToSubscribe(
                            videoTrack = track as RemoteVideoTrack,
                            exception = exception,
                            participant = this
                        )
                    }
                }
            } else {
                coroutineScope.launch {
                    delay(150)
                    addSubscribedMediaTrack(rtcTrack, sid, triesLeft - 1)
                }
            }
            return
        }

        val remoteTrack = track as RemoteTrack
        publication.track = track
        track.name = publication.trackName
        remoteTrack.sid = publication.trackSid

        when (publication) {
            is RemoteAudioTrackPublication -> listener?.onSubscribe(publication, this)
            is RemoteVideoTrackPublication -> listener?.onSubscribe(publication, this)
            else -> throw TrackException.InvalidTrackTypeException()
        }
    }

    fun addSubscribedDataTrack(rtcTrack: DataChannel, sid: Track.Sid, name: String) {
        val track = RemoteDataTrack(sid, name, rtcTrack)
        var publication = getTrackPublication(sid) as? RemoteDataTrackPublication

        if (publication != null) {
            publication.track = track
        } else {
            val trackInfo = Model.TrackInfo.newBuilder()
                .setSid(sid.sid)
                .setName(name)
                .setType(Model.TrackType.DATA)
                .build()
            publication = RemoteDataTrackPublication(info = trackInfo, track = track)
            addTrack(publication)
            if (hasInfo) {
                sendTrackPublishedEvent(publication)
            }
        }


        rtcTrack.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val newState = rtcTrack.state()
                if (newState == DataChannel.State.CLOSED) {
                    listener?.onUnsubscribe(publication, this@RemoteParticipant)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                listener?.onReceive(buffer.data, publication, this@RemoteParticipant)
            }
        })
        listener?.onSubscribe(dataTrack = publication, participant = this)
    }

    private fun unpublishTrack(trackSid: Track.Sid, sendUnpublish: Boolean) {
        val publication = tracks.remove(trackSid) ?: return

        when (publication) {
            is RemoteAudioTrackPublication -> audioTracks.remove(trackSid)
            is RemoteVideoTrackPublication -> videoTracks.remove(trackSid)
            is RemoteDataTrackPublication -> {
                dataTracks.remove(trackSid)
                publication.dataTrack?.rtcTrack?.unregisterObserver()
            }
            else -> throw TrackException.InvalidTrackTypeException()
        }

        if (publication.track != null) {
            // TODO: need to stop track?
            publication.track
            sendTrackUnsubscribedEvent(publication)
        }
        if (sendUnpublish) {
            sendTrackUnpublishedEvent(publication)
        }
    }

    private fun sendTrackUnsubscribedEvent(publication: TrackPublication) {
        when (publication) {
            is RemoteAudioTrackPublication -> listener?.onUnsubscribe(publication, this)
            is RemoteVideoTrackPublication -> listener?.onUnsubscribe(publication, this)
            is RemoteDataTrackPublication -> listener?.onUnsubscribe(publication, this)
            else -> throw TrackException.InvalidTrackTypeException()
        }
    }

    private fun sendTrackUnpublishedEvent(publication: TrackPublication) {
        when (publication) {
            is RemoteAudioTrackPublication -> listener?.onUnpublish(publication, this)
            is RemoteVideoTrackPublication -> listener?.onUnpublish(publication, this)
            is RemoteDataTrackPublication -> listener?.onUnpublish(publication, this)
            else -> throw TrackException.InvalidTrackTypeException()
        }
    }

    private fun sendTrackPublishedEvent(publication: RemoteTrackPublication) {
        when (publication) {
            is RemoteAudioTrackPublication -> listener?.onPublish(publication, this)
            is RemoteVideoTrackPublication -> listener?.onPublish(publication, this)
            is RemoteDataTrackPublication -> listener?.onPublish(publication, this)
            else -> throw TrackException.InvalidTrackTypeException()
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

    interface Listener {
        fun onPublish(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)
        fun onUnpublish(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)
        fun onPublish(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)
        fun onUnpublish(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)
        fun onPublish(dataTrack: RemoteDataTrackPublication, participant: RemoteParticipant)
        fun onUnpublish(dataTrack: RemoteDataTrackPublication, participant: RemoteParticipant)

        fun onEnable(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)
        fun onDisable(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)
        fun onEnable(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)
        fun onDisable(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)

        fun onSubscribe(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)
        fun onFailToSubscribe(
            audioTrack: RemoteAudioTrack,
            exception: Exception,
            participant: RemoteParticipant
        )

        fun onUnsubscribe(audioTrack: RemoteAudioTrackPublication, participant: RemoteParticipant)

        fun onSubscribe(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)
        fun onFailToSubscribe(
            videoTrack: RemoteVideoTrack,
            exception: Exception,
            participant: RemoteParticipant
        )

        fun onUnsubscribe(videoTrack: RemoteVideoTrackPublication, participant: RemoteParticipant)

        fun onSubscribe(dataTrack: RemoteDataTrackPublication, participant: RemoteParticipant)
        fun onFailToSubscribe(
            dataTrack: RemoteDataTrackPublication,
            exception: Exception,
            participant: RemoteParticipant
        )

        fun onUnsubscribe(dataTrack: RemoteDataTrackPublication, participant: RemoteParticipant)
        fun onReceive(
            data: ByteBuffer,
            dataTrack: RemoteDataTrackPublication,
            participant: RemoteParticipant
        )

        //fun networkQualityDidChange(networkQualityLevel: NetworkQualityLevel, participant: remoteParticipant)
        fun switchedOffVideo(track: RemoteVideoTrack, participant: RemoteParticipant)
        fun switchedOnVideo(track: RemoteVideoTrack, participant: RemoteParticipant)
//        fun onChangePublishPriority(videoTrack: RemoteVideoTrackPublication, priority: PublishPriority, participant: RemoteParticipant)
//        fun onChangePublishPriority(audioTrack: RemoteAudioTrackPublication, priority: PublishPriority, participant: RemoteParticipant)
//        fun onChangePublishPriority(dataTrack: RemoteDataTrackPublication, priority: PublishPriority, participant: RemoteParticipant)
    }

}