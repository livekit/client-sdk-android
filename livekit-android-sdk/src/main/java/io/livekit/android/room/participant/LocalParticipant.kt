package io.livekit.android.room.participant

import android.Manifest
import android.content.Context
import android.content.Intent
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import javax.inject.Named
import kotlin.math.abs
import kotlin.math.roundToInt

class LocalParticipant
@AssistedInject
internal constructor(
    @Assisted
    info: LivekitModels.ParticipantInfo,
    internal val engine: RTCEngine,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val screencastVideoTrackFactory: LocalScreencastVideoTrack.Factory,
    private val videoTrackFactory: LocalVideoTrack.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    coroutineDispatcher: CoroutineDispatcher,
) : Participant(info.sid, info.identity, coroutineDispatcher) {

    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults

    init {
        updateFromInfo(info)
    }

    private val localTrackPublications
        get() = tracks.values
            .mapNotNull { it as? LocalTrackPublication }
            .toList()

    /**
     * Creates an audio track, recording audio through the microphone with the given [options].
     *
     * @exception SecurityException will be thrown if [Manifest.permission.RECORD_AUDIO] permission is missing.
     */
    fun createAudioTrack(
        name: String = "",
        options: LocalAudioTrackOptions = audioTrackCaptureDefaults,
    ): LocalAudioTrack {
        return LocalAudioTrack.createTrack(context, peerConnectionFactory, options, name)
    }

    /**
     * Creates a video track, recording video through the camera with the given [options].
     *
     * @exception SecurityException will be thrown if [Manifest.permission.CAMERA] permission is missing.
     */
    fun createVideoTrack(
        name: String = "",
        options: LocalVideoTrackOptions = videoTrackCaptureDefaults.copy(),
    ): LocalVideoTrack {
        return LocalVideoTrack.createTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            videoTrackFactory,
        )
    }

    /**
     * Creates a screencast video track.
     *
     * @param mediaProjectionPermissionResultData The resultData returned from launching
     * [MediaProjectionManager.createScreenCaptureIntent()](https://developer.android.com/reference/android/media/projection/MediaProjectionManager#createScreenCaptureIntent()).
     */
    fun createScreencastTrack(
        name: String = "",
        mediaProjectionPermissionResultData: Intent,
    ): LocalScreencastVideoTrack {
        return LocalScreencastVideoTrack.createTrack(
            mediaProjectionPermissionResultData,
            peerConnectionFactory,
            context,
            name,
            LocalVideoTrackOptions(isScreencast = true),
            eglBase,
            screencastVideoTrackFactory
        )
    }

    override fun getTrackPublication(source: Track.Source): LocalTrackPublication? {
        return super.getTrackPublication(source) as? LocalTrackPublication
    }

    override fun getTrackPublicationByName(name: String): LocalTrackPublication? {
        return super.getTrackPublicationByName(name) as? LocalTrackPublication
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        setTrackEnabled(Track.Source.CAMERA, enabled)
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        setTrackEnabled(Track.Source.MICROPHONE, enabled)
    }

    /**
     * @param mediaProjectionPermissionResultData The resultData returned from launching
     * [MediaProjectionManager.createScreenCaptureIntent()](https://developer.android.com/reference/android/media/projection/MediaProjectionManager#createScreenCaptureIntent()).
     * @throws IllegalArgumentException if attempting to enable screenshare without [mediaProjectionPermissionResultData]
     */
    suspend fun setScreenShareEnabled(
        enabled: Boolean,
        mediaProjectionPermissionResultData: Intent? = null
    ) {
        setTrackEnabled(Track.Source.SCREEN_SHARE, enabled, mediaProjectionPermissionResultData)
    }

    private suspend fun setTrackEnabled(
        source: Track.Source,
        enabled: Boolean,
        mediaProjectionPermissionResultData: Intent? = null

    ) {
        val pub = getTrackPublication(source)
        if (enabled) {
            if (pub != null) {
                pub.muted = false
            } else {
                when (source) {
                    Track.Source.CAMERA -> {
                        val track = createVideoTrack()
                        track.startCapture()
                        publishVideoTrack(track)
                    }
                    Track.Source.MICROPHONE -> {
                        val track = createAudioTrack()
                        publishAudioTrack(track)
                    }
                    Track.Source.SCREEN_SHARE -> {
                        if (mediaProjectionPermissionResultData == null) {
                            throw IllegalArgumentException("Media Projection permission result data is required to create a screen share track.")
                        }
                        val track =
                            createScreencastTrack(mediaProjectionPermissionResultData = mediaProjectionPermissionResultData)
                        publishVideoTrack(track)
                    }
                }
            }
        } else {
            pub?.track?.let { track ->
                // screenshare cannot be muted, unpublish instead
                if (pub.source == Track.Source.SCREEN_SHARE) {
                    unpublishTrack(track)
                } else {
                    pub.muted = true
                }
            }
        }
    }

    suspend fun publishAudioTrack(
        track: LocalAudioTrack,
        options: AudioTrackPublishOptions = AudioTrackPublishOptions(
            null,
            audioTrackPublishDefaults
        ),
        publishListener: PublishListener? = null
    ) {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return
        }

        val cid = track.rtcTrack.id()
        val builder = LivekitRtc.AddTrackRequest.newBuilder().apply {
            disableDtx = !options.dtx
            source = LivekitModels.TrackSource.MICROPHONE
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
        internalListener?.onTrackPublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackPublished(this, publication), scope)
    }

    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        options: VideoTrackPublishOptions = VideoTrackPublishOptions(null, videoTrackPublishDefaults),
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
            source = if(track.options.isScreencast){
                LivekitModels.TrackSource.SCREEN_SHARE
            } else {
                LivekitModels.TrackSource.CAMERA
            }
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
        internalListener?.onTrackPublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackPublished(this, publication), scope)
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

            fun calculateScale(parameter: VideoCaptureParameter): Double {
                return height / parameter.height.toDouble()
            }

            fun checkEvenDimensions(parameter: VideoCaptureParameter): Boolean {
                fun isEven(value: Double) = ((value.roundToInt()) % 2 == 0)
                val scale = calculateScale(parameter)

                return isEven(parameter.height * scale) && isEven(parameter.width * scale)
            }

            // if resolution is high enough, we send both h and q res.
            // otherwise only send h
            if (width >= 960) {
                val hasEvenDimensions =
                    checkEvenDimensions(midPreset.capture) && checkEvenDimensions(lowPreset.capture)
                val midScale = if (hasEvenDimensions) calculateScale(midPreset.capture) else 2.0
                val lowScale = if (hasEvenDimensions) calculateScale(lowPreset.capture) else 4.0

                encodings.add(
                    midPreset.encoding.toRtpEncoding(
                        "h",
                        midScale
                    )
                )
                encodings.add(
                    lowPreset.encoding.toRtpEncoding(
                        "q",
                        lowScale
                    )
                )
            } else {
                val hasEvenDimensions = checkEvenDimensions(lowPreset.capture)
                val lowScale = if (hasEvenDimensions) calculateScale(lowPreset.capture) else 2.0
                encodings.add(
                    lowPreset.encoding.toRtpEncoding(
                        "h",
                        lowScale
                    )
                )
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
            else -> {
            }
        }
        val senders = engine.publisher.peerConnection.senders ?: return
        for (sender in senders) {
            val t = sender.track() ?: continue
            if (t.id() == track.rtcTrack.id()) {
                engine.publisher.peerConnection.removeTrack(sender)
            }
        }
        track.stop()
        internalListener?.onTrackUnpublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackUnpublished(this, publication), scope)
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
                publication.muted = ti.muted
            }
        }
    }

    /**
     * @suppress
     */
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

abstract class BaseVideoTrackPublishOptions {
    abstract val videoEncoding: VideoEncoding?
    abstract val simulcast: Boolean
    //val videoCodec: VideoCodec? = null,
}

data class VideoTrackPublishDefaults(
    override val videoEncoding: VideoEncoding? = null,
    override val simulcast: Boolean = false
) : BaseVideoTrackPublishOptions()

data class VideoTrackPublishOptions(
    override val name: String? = null,
    override val videoEncoding: VideoEncoding? = null,
    override val simulcast: Boolean = false
) : BaseVideoTrackPublishOptions(), TrackPublishOptions {
    constructor(
        name: String? = null,
        base: BaseVideoTrackPublishOptions
    ) : this(
        name,
        base.videoEncoding,
        base.simulcast
    )
}

abstract class BaseAudioTrackPublishOptions {
    abstract val audioBitrate: Int?
    abstract val dtx: Boolean
}

data class AudioTrackPublishDefaults(
    override val audioBitrate: Int? = null,
    override val dtx: Boolean = true
) : BaseAudioTrackPublishOptions()

data class AudioTrackPublishOptions(
    override val name: String? = null,
    override val audioBitrate: Int? = null,
    override val dtx: Boolean = true
) : BaseAudioTrackPublishOptions(), TrackPublishOptions {
    constructor(
        name: String? = null,
        base: BaseAudioTrackPublishOptions
    ) : this(
        name,
        base.audioBitrate,
        base.dtx
    )
}