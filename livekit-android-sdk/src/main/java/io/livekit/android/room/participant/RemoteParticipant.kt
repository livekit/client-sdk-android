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
) : Participant(sid, name) {
    /**
     * @suppress
     */
    constructor(info: LivekitModels.ParticipantInfo) : this(info.sid, info.identity) {
        updateFromInfo(info)
    }

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob())

    fun getTrackPublication(sid: String): TrackPublication? = tracks[sid]

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
                internalListener?.onTrackPublished(publication, this)
                listener?.onTrackPublished(publication, this)
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
            KIND_AUDIO -> AudioTrack(rtcTrack = mediaTrack as AudioTrack, name = "")
            KIND_VIDEO -> VideoTrack(rtcTrack = mediaTrack as VideoTrack, name = "")
            else -> throw TrackException.InvalidTrackTypeException("invalid track type: $kind")
        }

        if (publication == null) {
            if (triesLeft == 0) {
                val message = "Could not find published track with sid: $sid"
                val exception = TrackException.InvalidTrackStateException(message)
                Timber.e { "remote participant ${this.sid} --- $message" }

                internalListener?.onTrackSubscriptionFailed(sid, exception, this)
                listener?.onTrackSubscriptionFailed(sid, exception, this)
            } else {
                coroutineScope.launch {
                    delay(150)
                    addSubscribedMediaTrack(mediaTrack, sid, triesLeft - 1)
                }
            }
            return
        }

        publication.track = track
        track.name = publication.name
        track.sid = publication.sid
        addTrackPublication(publication)

        // TODO: how does mediatrack send ended event?

        internalListener?.onTrackSubscribed(track, publication, this)
        listener?.onTrackSubscribed(track, publication, this)
    }

    /**
     * @suppress
     */
    fun addSubscribedDataTrack(dataChannel: DataChannel, sid: String, name: String) {
        val track = DataTrack(name, dataChannel)
        var publication = getTrackPublication(sid)

        if (publication == null) {
            val trackInfo = LivekitModels.TrackInfo.newBuilder()
                .setSid(sid)
                .setName(name)
                .setType(LivekitModels.TrackType.DATA)
                .build()
            publication = TrackPublication(info = trackInfo)
            addTrackPublication(publication)
            if (hasInfo) {
                internalListener?.onTrackPublished(publication, this)
                listener?.onTrackPublished(publication, this)
            }
        }
        publication.track = track

        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val newState = dataChannel.state()
                if (newState == DataChannel.State.CLOSED) {
                    publication.track = null
                    internalListener?.onTrackUnsubscribed(track, publication, this@RemoteParticipant)
                    listener?.onTrackUnsubscribed(track, publication, this@RemoteParticipant)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                internalListener?.onDataReceived(buffer.data, track, this@RemoteParticipant)
                listener?.onDataReceived(buffer.data, track, this@RemoteParticipant)
            }
        })
        internalListener?.onTrackSubscribed(track, publication, participant = this)
        listener?.onTrackSubscribed(track, publication, this)
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
            internalListener?.onTrackUnsubscribed(track, publication, this)
            listener?.onTrackUnsubscribed(track, publication, this)
        }
        if (sendUnpublish) {
            internalListener?.onTrackUnpublished(publication, this)
            listener?.onTrackUnpublished(publication, this)
        }
    }

    companion object {
        private const val KIND_AUDIO = "audio"
        private const val KIND_VIDEO = "video"
    }
}
