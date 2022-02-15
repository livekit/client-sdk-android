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
import io.livekit.android.room.ConnectionState
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.track.*
import io.livekit.android.room.util.EncodingUtils
import io.livekit.android.room.util.EncodingUtils.findEvenScaleDownBy
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import javax.inject.Named
import kotlin.math.max

class LocalParticipant
@AssistedInject
internal constructor(
    @Assisted
    info: LivekitModels.ParticipantInfo,
    @Assisted
    private val dynacast: Boolean,
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

    private var isReconnecting = false

    /**
     * Holds on to publishes that need to be republished after a full reconnect.
     */
    private var publishes = mutableMapOf<Track, TrackPublishOptions>()

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
        val published = publishTrackImpl(
            track,
            requestConfig = {
                disableDtx = !options.dtx
                source = LivekitModels.TrackSource.MICROPHONE
            },
            publishListener = publishListener,
        )

        if (published) {
            publishes[track] = options
        }
    }

    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        options: VideoTrackPublishOptions = VideoTrackPublishOptions(null, videoTrackPublishDefaults),
        publishListener: PublishListener? = null
    ) {

        val encodings = computeVideoEncodings(track.dimensions, options)
        val videoLayers =
            EncodingUtils.videoLayersFromEncodings(track.dimensions.width, track.dimensions.height, encodings)

        val published = publishTrackImpl(
            track,
            requestConfig = {
                width = track.dimensions.width
                height = track.dimensions.height
                source = if (track.options.isScreencast) {
                    LivekitModels.TrackSource.SCREEN_SHARE
                } else {
                    LivekitModels.TrackSource.CAMERA
                }
                addAllLayers(videoLayers)
            },
            encodings = encodings,
            publishListener = publishListener
        )

        if (published) {
            publishes[track] = options
        }
    }


    /**
     * @return true if the track publish was successful.
     */
    private suspend fun publishTrackImpl(
        track: Track,
        requestConfig: LivekitRtc.AddTrackRequest.Builder.() -> Unit,
        encodings: List<RtpParameters.Encoding> = emptyList(),
        publishListener: PublishListener? = null
    ): Boolean {
        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return false
        }

        val cid = track.rtcTrack.id()
        val builder = LivekitRtc.AddTrackRequest.newBuilder().apply {
            this.requestConfig()
        }
        val trackInfo = engine.addTrack(
            cid = cid,
            name = track.name,
            kind = track.kind.toProto(),
            builder = builder
        )
        val transInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid),
            encodings
        )
        val transceiver = engine.publisher.peerConnection.addTransceiver(track.rtcTrack, transInit)

        when (track) {
            is LocalVideoTrack -> track.transceiver = transceiver
            is LocalAudioTrack -> track.transceiver = transceiver
            else -> {
                throw IllegalArgumentException("Trying to publish a non local track of type ${track.javaClass}")
            }
        }

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return false
        }

        // TODO: enable setting preferred codec

        val publication = LocalTrackPublication(trackInfo, track, this)
        addTrackPublication(publication)

        publishListener?.onPublishSuccess(publication)
        internalListener?.onTrackPublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackPublished(this, publication), scope)

        return true
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
            encoding = EncodingUtils.determineAppropriateEncoding(width, height)
            LKLog.d { "using video encoding: $encoding" }
        }

        val encodings = mutableListOf<RtpParameters.Encoding>()
        if (simulcast) {

            val presets = EncodingUtils.presetsForResolution(width, height)
            val midPreset = presets[1]
            val lowPreset = presets[0]


            fun addEncoding(videoEncoding: VideoEncoding, scale: Double) {
                if (encodings.size >= EncodingUtils.VIDEO_RIDS.size) {
                    throw IllegalStateException("Attempting to add more encodings than we have rids for!")
                }
                // encodings is mutable, so this will grab next available rid
                val rid = EncodingUtils.VIDEO_RIDS[encodings.size]
                encodings.add(videoEncoding.toRtpEncoding(rid, scale))
            }

            // if resolution is high enough, we send both h and q res.
            // otherwise only send h
            val size = max(width, height)
            if (size >= 960) {
                var lowScale = findEvenScaleDownBy(width, height, lowPreset.capture.width, lowPreset.capture.height)
                var midScale = findEvenScaleDownBy(width, height, midPreset.capture.width, midPreset.capture.height)

                if (midScale == null || lowScale == null) {
                    lowScale = 4.0
                    midScale = 2.0
                }
                addEncoding(lowPreset.encoding, lowScale)
                addEncoding(midPreset.encoding, midScale)
            } else {
                val lowScale = findEvenScaleDownBy(width, height, lowPreset.capture.width, lowPreset.capture.height) ?: 2.0
                addEncoding(lowPreset.encoding, lowScale)
            }
            addEncoding(encoding, 1.0)
        } else {
            encodings.add(encoding.toRtpEncoding())
        }

        // Make largest size at front. addTransceiver seems to fail if ordered from smallest to largest.
        encodings.reverse()
        return encodings
    }


    /**
     * Control who can subscribe to LocalParticipant's published tracks.
     *
     * By default, all participants can subscribe. This allows fine-grained control over
     * who is able to subscribe at a participant and track level.
     *
     * Note: if access is given at a track-level (i.e. both [allParticipantsAllowed] and
     * [ParticipantTrackPermission.allTracksAllowed] are false), any newer published tracks
     * will not grant permissions to any participants and will require a subsequent
     * permissions update to allow subscription.
     *
     * @param allParticipantsAllowed Allows all participants to subscribe all tracks.
     *  Takes precedence over [participantTrackPermissions] if set to true.
     *  By default this is set to true.
     * @param participantTrackPermissions Full list of individual permissions per
     *  participant/track. Any omitted participants will not receive any permissions.
     */
    fun setTrackSubscriptionPermissions(
        allParticipantsAllowed: Boolean,
        participantTrackPermissions: List<ParticipantTrackPermission> = emptyList()
    ) {
        engine.updateSubscriptionPermissions(allParticipantsAllowed, participantTrackPermissions)
    }

    fun unpublishTrack(track: Track) {
        val publication = localTrackPublications.firstOrNull { it.track == track }
        if (publication === null) {
            LKLog.d { "this track was never published." }
            return
        }

        publishes.remove(track)

        val sid = publication.sid
        tracks = tracks.toMutableMap().apply { remove(sid) }

        if (engine.connectionState == ConnectionState.CONNECTED) {
            val senders = engine.publisher.peerConnection.senders
            for (sender in senders) {
                val t = sender.track() ?: continue
                if (t.id() == track.rtcTrack.id()) {
                    engine.publisher.peerConnection.removeTrack(sender)
                }
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
    suspend fun publishData(
        data: ByteArray,
        reliability: DataPublishReliability = DataPublishReliability.RELIABLE,
        destination: List<String>? = null
    ) {
        if (data.size > RTCEngine.MAX_DATA_PACKET_SIZE) {
            throw IllegalArgumentException("cannot publish data larger than " + RTCEngine.MAX_DATA_PACKET_SIZE)
        }

        val kind = when (reliability) {
            DataPublishReliability.RELIABLE -> LivekitModels.DataPacket.Kind.RELIABLE
            DataPublishReliability.LOSSY -> LivekitModels.DataPacket.Kind.LOSSY
        }
        val packetBuilder = LivekitModels.UserPacket.newBuilder()
            .setPayload(ByteString.copyFrom(data))
            .setParticipantSid(sid)
        if (destination != null) {
            packetBuilder.addAllDestinationSids(destination)
        }
        val dataPacket = LivekitModels.DataPacket.newBuilder()
            .setUser(packetBuilder)
            .setKind(kind)
            .build()

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

    fun handleSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        if (!dynacast) {
            return
        }

        val trackSid = subscribedQualityUpdate.trackSid
        val qualities = subscribedQualityUpdate.subscribedQualitiesList
        val pub = tracks[trackSid] ?: return
        val track = pub.track as? LocalVideoTrack ?: return

        val sender = track.transceiver?.sender ?: return
        val parameters = sender.parameters ?: return
        val encodings = parameters.encodings ?: return

        var hasChanged = false
        for (quality in qualities) {
            val rid = EncodingUtils.ridForVideoQuality(quality.quality) ?: continue
            val encoding = encodings.firstOrNull { it.rid == rid }
            // use low quality layer settings for non-simulcasted streams
                ?: encodings.takeIf { it.size == 1 && quality.quality == LivekitModels.VideoQuality.LOW }?.first()
                ?: continue
            if (encoding.active != quality.enabled) {
                hasChanged = true
                encoding.active = quality.enabled
                LKLog.v { "setting layer ${quality.quality} to ${quality.enabled}" }
            }
        }

        if (hasChanged) {
            sender.parameters = parameters
        }
    }

    fun prepareForFullReconnect() {
        val pubs = localTrackPublications // creates a copy, so is safe from the following removal.
        tracks = tracks.toMutableMap().apply { clear() }

        for (publication in pubs) {
            internalListener?.onTrackUnpublished(publication, this)
            eventBus.postEvent(ParticipantEvent.LocalTrackUnpublished(this, publication), scope)
        }
    }

    suspend fun republishTracks() {
        for ((track, options) in publishes) {
            when (track) {
                is LocalAudioTrack -> publishAudioTrack(track, options as AudioTrackPublishOptions, null)
                is LocalVideoTrack -> publishVideoTrack(track, options as VideoTrackPublishOptions, null)
                else -> throw IllegalStateException("LocalParticipant has a non local track publish?")
            }
        }
    }

    fun cleanup() {
        for (pub in tracks.values) {
            val track = pub.track

            if (track != null) {
                track.stop()
                unpublishTrack(track)
            }
        }
    }

    fun dispose() {
        cleanup()
        scope.cancel()
    }

    interface PublishListener {
        fun onPublishSuccess(publication: TrackPublication) {}
        fun onPublishFailure(exception: Exception) {}
    }

    @AssistedFactory
    interface Factory {
        fun create(info: LivekitModels.ParticipantInfo, dynacast: Boolean): LocalParticipant
    }

    companion object {
    }
}

internal fun LocalParticipant.publishTracksInfo(): List<LivekitRtc.TrackPublishedResponse> {
    return tracks.values.mapNotNull { trackPub ->
        val track = trackPub.track ?: return@mapNotNull null

        LivekitRtc.TrackPublishedResponse.newBuilder()
            .setCid(track.rtcTrack.id())
            .setTrack(trackPub.trackInfo)
            .build()
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
    override val simulcast: Boolean = true
) : BaseVideoTrackPublishOptions()

data class VideoTrackPublishOptions(
    override val name: String? = null,
    override val videoEncoding: VideoEncoding? = null,
    override val simulcast: Boolean = true
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

data class ParticipantTrackPermission(
    /**
     * The participant id this permission applies to.
     */
    val participantSid: String,
    /**
     * If set to true, the target participant can subscribe to all tracks from the local participant.
     *
     * Takes precedence over [allowedTrackSids].
     */
    val allTracksAllowed: Boolean,
    /**
     * The list of track ids that the target participant can subscribe to.
     */
    val allowedTrackSids: List<String> = emptyList()
) {
    fun toProto(): LivekitRtc.TrackPermission {
        return LivekitRtc.TrackPermission.newBuilder()
            .setParticipantSid(participantSid)
            .setAllTracks(allTracksAllowed)
            .addAllTrackSids(allowedTrackSids)
            .build()
    }
}

sealed class PublishRecord() {
    data class AudioTrackPublishRecord(
        val track: LocalAudioTrack,
        val options: AudioTrackPublishOptions
    )

    data class VideoTrackPublishRecord(
        val track: LocalVideoTrack,
        val options: VideoTrackPublishOptions
    )
}