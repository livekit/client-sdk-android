package io.livekit.android.room.participant

import io.livekit.android.room.SignalClient
import io.livekit.android.room.track.*
import io.livekit.android.util.CloseableCoroutineScope
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.LivekitModels
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoTrack

class RemoteParticipant(
    sid: String,
    identity: String? = null,
    val signalClient: SignalClient,
    private val ioDispatcher: CoroutineDispatcher,
) : Participant(sid, identity) {
    /**
     * @suppress
     */
    constructor(
        info: LivekitModels.ParticipantInfo,
        signalClient: SignalClient,
        ioDispatcher: CoroutineDispatcher
    ) : this(
        info.sid,
        info.identity,
        signalClient,
        ioDispatcher,
    ) {
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
                publication = RemoteTrackPublication(
                    trackInfo,
                    participant = this,
                    ioDispatcher = ioDispatcher
                )

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
    fun addSubscribedMediaTrack(
        mediaTrack: MediaStreamTrack,
        sid: String,
        autoManageVideo: Boolean = false,
        triesLeft: Int = 20
    ) {
        val publication = getTrackPublication(sid)
        val track: Track = when (val kind = mediaTrack.kind()) {
            KIND_AUDIO -> AudioTrack(rtcTrack = mediaTrack as AudioTrack, name = "")
            KIND_VIDEO -> RemoteVideoTrack(
                rtcTrack = mediaTrack as VideoTrack,
                name = "",
                autoManageVideo = autoManageVideo,
                dispatcher = ioDispatcher
            )
            else -> throw TrackException.InvalidTrackTypeException("invalid track type: $kind")
        }

        if (publication == null) {
            if (triesLeft == 0) {
                val message = "Could not find published track with sid: $sid"
                val exception = TrackException.InvalidTrackStateException(message)
                LKLog.e { "remote participant ${this.sid} --- $message" }

                internalListener?.onTrackSubscriptionFailed(sid, exception, this)
                listener?.onTrackSubscriptionFailed(sid, exception, this)
            } else {
                coroutineScope.launch {
                    delay(150)
                    addSubscribedMediaTrack(mediaTrack, sid, autoManageVideo, triesLeft - 1)
                }
            }
            return
        }

        publication.track = track
        track.name = publication.name
        track.sid = publication.sid
        addTrackPublication(publication)
        track.start()

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
