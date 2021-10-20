package io.livekit.android.room.participant

import android.Manifest
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
import kotlin.math.abs

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

    /**
     * Creates an audio track, recording audio through the microphone with the given [options].
     *
     * @exception SecurityException will be thrown if [Manifest.permission.RECORD_AUDIO] permission is missing.
     */
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
        return LocalAudioTrack.createTrack(context, peerConnectionFactory, audioConstraints, name)
    }

    /**
     * Creates a video track, recording video through the camera with the given [options].
     *
     * @exception SecurityException will be thrown if [Manifest.permission.CAMERA] permission is missing.
     */
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
        options: AudioTrackPublishOptions = AudioTrackPublishOptions(),
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val builder = LivekitRtc.AddTrackRequest.newBuilder().apply {
            disableDtx = !options.dtx
        }
        val trackInfo = engine.addTrack(
            cid = cid,
            name = track.name,
            kind = track.kind.toProto(),
            builder = builder
        )
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
        options: VideoTrackPublishOptions = VideoTrackPublishOptions(),
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val builder = LivekitRtc.AddTrackRequest.newBuilder().apply {
            width = track.dimensions.width
            height = track.dimensions.height
        }
        val trackInfo = engine.addTrack(
            cid = cid,
            name = track.name,
            kind = LivekitModels.TrackType.VIDEO,
            builder = builder
        )
        val encodings = computeVideoEncodings(track.dimensions, options)
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid),
            encodings
        )
        val transceiver = engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)
        track.transceiver = transceiver

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return
        }

        // TODO: enable setting preferred codec

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)
        publishListener?.onPublishSuccess(publication)
    }

    private fun computeVideoEncodings(
        dimensions: Track.Dimensions,
        options: VideoTrackPublishOptions
    ): List<RtpParameters.Encoding> {
        val (width, height) = dimensions
        var encoding = options.videoEncoding
        val simulcast = options.simulcast

        if ((encoding == null && !simulcast) || width == 0 || height == 0) {
            return emptyList()
        }

        if (encoding == null) {
            encoding = determineAppropriateEncoding(width, height)
            LKLog.d { "using video encoding: $encoding" }
        }

        val encodings = mutableListOf<RtpParameters.Encoding>()
        if (simulcast) {
            encodings.add(encoding.toRtpEncoding("f"))

            val presets = presetsForResolution(width, height)
            val midPreset = presets[1]
            val lowPreset = presets[0]

            // if resolution is high enough, we send both h and q res.
            // otherwise only send h
            if (width >= 960) {
                encodings.add(midPreset.encoding.toRtpEncoding("h", 2.0))
                encodings.add(lowPreset.encoding.toRtpEncoding("q", 4.0))
            } else {
                encodings.add(lowPreset.encoding.toRtpEncoding("h", 2.0))
            }
        } else {
            encodings.add(encoding.toRtpEncoding())
        }
        return encodings
    }

    private fun determineAppropriateEncoding(width: Int, height: Int): VideoEncoding {
        val presets = presetsForResolution(width, height)

        return presets
            .last { width >= it.capture.width && height >= it.capture.height }
            .encoding
    }

    private fun presetsForResolution(width: Int, height: Int): List<VideoPreset> {
        val aspectRatio = width.toFloat() / height
        if (abs(aspectRatio - 16f / 9f) < abs(aspectRatio - 4f / 3f)) {
            return PRESETS_16_9
        } else {
            return PRESETS_4_3
        }
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

    companion object {
        private val PRESETS_16_9 = listOf(
            VideoPreset169.QVGA,
            VideoPreset169.VGA,
            VideoPreset169.QHD,
            VideoPreset169.HD,
            VideoPreset169.FHD
        )

        private val PRESETS_4_3 = listOf(
            VideoPreset43.QVGA,
            VideoPreset43.VGA,
            VideoPreset43.QHD,
            VideoPreset43.HD,
            VideoPreset43.FHD
        )
    }
}

interface TrackPublishOptions {
    val name: String?
}

data class VideoTrackPublishOptions(
    override val name: String? = null,
    val videoEncoding: VideoEncoding? = null,
    //val videoCodec: VideoCodec? = null,
    val simulcast: Boolean = false
) : TrackPublishOptions

data class AudioTrackPublishOptions(
    override val name: String? = null,
    val audioBitrate: Int? = null,
    val dtx: Boolean = true
) : TrackPublishOptions