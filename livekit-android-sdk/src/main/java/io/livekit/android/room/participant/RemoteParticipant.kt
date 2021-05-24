package io.livekit.android.room.participant

import com.github.ajalt.timberkt.Timber
import io.livekit.android.room.SignalClient
import io.livekit.android.room.track.*
import io.livekit.android.util.CloseableCoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoTrack

class RemoteParticipant(
    val signalClient: SignalClient,
    sid: String,
    identity: String? = null,
) : Participant(sid, identity) {
    /**
     * @suppress
     */
    constructor(signalClient: SignalClient, info: LivekitModels.ParticipantInfo) : this(signalClient, info.sid, info.identity) {
        updateFromInfo(info)
    }

    private val coroutineScope = CloseableCoroutineScope(SupervisorJob())

    fun getTrackPublication(sid: String): RemoteTrackPublication? = tracks[sid] as? RemoteTrackPublication

    /**
     * @suppress
     */
    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        val hadInfo = hasInfo
        super.updateFromInfo(info)

        val validTrackPublication = mutableMapOf<String, RemoteTrackPublication>()
        val newTrackPublications = mutableMapOf<String, RemoteTrackPublication>()

        for (trackInfo in info.tracksList) {
            val trackSid = trackInfo.sid
            var publication = getTrackPublication(trackSid)

            if (publication == null) {
                publication = RemoteTrackPublication(trackInfo, participant = this)

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

    fun unpublishTrack(trackSid: String, sendUnpublish: Boolean = false) {
        val publication = tracks.remove(trackSid) as? RemoteTrackPublication ?: return
        when (publication.kind) {
            Track.Kind.AUDIO -> audioTracks.remove(trackSid)
            Track.Kind.VIDEO -> videoTracks.remove(trackSid)
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
