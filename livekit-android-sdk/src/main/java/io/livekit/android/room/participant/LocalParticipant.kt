/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room.participant

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.protobuf.ByteString
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.dagger.CapabilitiesGetter
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.room.ConnectionState
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.RTCEngine
import io.livekit.android.room.Room
import io.livekit.android.room.TrackBitrateInfo
import io.livekit.android.room.isSVCCodec
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.util.EncodingUtils
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import io.livekit.android.webrtc.sortVideoCodecPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.LivekitRtc.AddTrackRequest
import livekit.LivekitRtc.SimulcastCodec
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.RtpTransceiver.RtpTransceiverInit
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoProcessor
import javax.inject.Named
import kotlin.math.max

class LocalParticipant
@AssistedInject
internal constructor(
    @Assisted
    internal var dynacast: Boolean,
    internal val engine: RTCEngine,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val screencastVideoTrackFactory: LocalScreencastVideoTrack.Factory,
    private val videoTrackFactory: LocalVideoTrack.Factory,
    private val audioTrackFactory: LocalAudioTrack.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    coroutineDispatcher: CoroutineDispatcher,
    @Named(InjectionNames.SENDER)
    private val capabilitiesGetter: CapabilitiesGetter,
) : Participant(Sid(""), null, coroutineDispatcher) {

    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults
    var screenShareTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::screenShareTrackCaptureDefaults
    var screenShareTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::screenShareTrackPublishDefaults

    private var republishes: List<LocalTrackPublication>? = null
    private val localTrackPublications
        get() = trackPublications.values
            .mapNotNull { it as? LocalTrackPublication }
            .toList()

    private val jobs = mutableMapOf<Any, Job>()

    // For ensuring that only one caller can execute setTrackEnabled at a time.
    // Without it, there's a potential to create multiple of the same source,
    // Camera has deadlock issues with multiple CameraCapturers trying to activate/stop.
    private val sourcePubLocks = Track.Source.values()
        .associate { source -> source to Mutex() }

    /**
     * Creates an audio track, recording audio through the microphone with the given [options].
     *
     * @param name The name of the track.
     * @param options The capture options to use for this track, or [Room.audioTrackCaptureDefaults] if none is passed.
     * @exception SecurityException will be thrown if [Manifest.permission.RECORD_AUDIO] permission is missing.
     */
    fun createAudioTrack(
        name: String = "",
        options: LocalAudioTrackOptions = audioTrackCaptureDefaults,
    ): LocalAudioTrack {
        return LocalAudioTrack.createTrack(context, peerConnectionFactory, options, audioTrackFactory, name)
    }

    /**
     * Creates a video track, recording video through the supplied [capturer].
     *
     * This method will call [VideoCapturer.initialize] and handle the lifecycle of
     * [SurfaceTextureHelper].
     *
     * @param name The name of the track.
     * @param capturer The capturer to use for this track.
     * @param options The capture options to use for this track, or [Room.videoTrackCaptureDefaults] if none is passed.
     * @param videoProcessor A video processor to attach to this track that can modify the frames before publishing.
     */
    fun createVideoTrack(
        name: String = "",
        capturer: VideoCapturer,
        options: LocalVideoTrackOptions = videoTrackCaptureDefaults.copy(),
        videoProcessor: VideoProcessor? = null,
    ): LocalVideoTrack {
        return LocalVideoTrack.createTrack(
            peerConnectionFactory = peerConnectionFactory,
            context = context,
            name = name,
            capturer = capturer,
            options = options,
            rootEglBase = eglBase,
            trackFactory = videoTrackFactory,
            videoProcessor = videoProcessor,
        )
    }

    /**
     * Creates a video track, recording video through the camera with the given [options].
     *
     * Note: If using this in conjunction with [setCameraEnabled], ensure that your created
     * camera track is published first before using [setCameraEnabled]. Otherwise, the LiveKit
     * SDK will attempt to create its own camera track to manage, and will cause issues since
     * generally only one camera session can be active at a time.
     *
     * @param name The name of the track
     * @param options The capture options to use for this track, or [Room.videoTrackCaptureDefaults] if none is passed.
     * @param videoProcessor A video processor to attach to this track that can modify the frames before publishing.
     * @exception SecurityException will be thrown if [Manifest.permission.CAMERA] permission is missing.
     */
    fun createVideoTrack(
        name: String = "",
        options: LocalVideoTrackOptions = videoTrackCaptureDefaults.copy(),
        videoProcessor: VideoProcessor? = null,
    ): LocalVideoTrack {
        return LocalVideoTrack.createCameraTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            videoTrackFactory,
            videoProcessor = videoProcessor,
        )
    }

    /**
     * Creates a screencast video track.
     *
     * @param name The name of the track.
     * @param mediaProjectionPermissionResultData The resultData returned from launching
     * [MediaProjectionManager.createScreenCaptureIntent()](https://developer.android.com/reference/android/media/projection/MediaProjectionManager#createScreenCaptureIntent()).
     * @param options The capture options to use for this track, or [Room.screenShareTrackCaptureDefaults] if none is passed.
     * @param videoProcessor A video processor to attach to this track that can modify the frames before publishing.
     */
    fun createScreencastTrack(
        name: String = "",
        mediaProjectionPermissionResultData: Intent,
        options: LocalVideoTrackOptions = screenShareTrackCaptureDefaults.copy(),
        videoProcessor: VideoProcessor? = null,
    ): LocalScreencastVideoTrack {
        val screencastOptions = options.copy(isScreencast = true)
        return LocalScreencastVideoTrack.createTrack(
            mediaProjectionPermissionResultData,
            peerConnectionFactory,
            context,
            name,
            screencastOptions,
            eglBase,
            screencastVideoTrackFactory,
            videoProcessor,
        )
    }

    override fun getTrackPublication(source: Track.Source): LocalTrackPublication? {
        return super.getTrackPublication(source) as? LocalTrackPublication
    }

    override fun getTrackPublicationByName(name: String): LocalTrackPublication? {
        return super.getTrackPublicationByName(name) as? LocalTrackPublication
    }

    /**
     * If set to enabled, creates and publishes a camera video track if not already done, and starts the camera.
     *
     * If set to disabled, mutes and stops the camera.
     *
     * This will use capture and publish default options from [Room].
     *
     * @see Room.videoTrackCaptureDefaults
     * @see Room.videoTrackPublishDefaults
     */
    suspend fun setCameraEnabled(enabled: Boolean) {
        setTrackEnabled(Track.Source.CAMERA, enabled)
    }

    /**
     * If set to enabled, creates and publishes a microphone audio track if not already done, and unmutes the mic.
     *
     * If set to disabled, mutes the mic.
     *
     * This will use capture and publish default options from [Room].
     *
     * @see Room.audioTrackCaptureDefaults
     * @see Room.audioTrackPublishDefaults
     */
    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        setTrackEnabled(Track.Source.MICROPHONE, enabled)
    }

    /**
     * If set to enabled, creates and publishes a screenshare video track.
     *
     * If set to disabled, unpublishes the screenshare video track.
     *
     * This will use capture and publish default options from [Room].
     *
     * @param mediaProjectionPermissionResultData The resultData returned from launching
     * [MediaProjectionManager.createScreenCaptureIntent()](https://developer.android.com/reference/android/media/projection/MediaProjectionManager#createScreenCaptureIntent()).
     * @throws IllegalArgumentException if attempting to enable screenshare without [mediaProjectionPermissionResultData]
     * @see Room.screenShareTrackCaptureDefaults
     * @see Room.screenShareTrackPublishDefaults
     */
    suspend fun setScreenShareEnabled(
        enabled: Boolean,
        mediaProjectionPermissionResultData: Intent? = null,
    ) {
        setTrackEnabled(Track.Source.SCREEN_SHARE, enabled, mediaProjectionPermissionResultData)
    }

    private suspend fun setTrackEnabled(
        source: Track.Source,
        enabled: Boolean,
        mediaProjectionPermissionResultData: Intent? = null,
    ) {
        val pubLock = sourcePubLocks[source]!!
        pubLock.withLock {
            val pub = getTrackPublication(source)
            if (enabled) {
                if (pub != null) {
                    pub.muted = false
                    if (source == Track.Source.CAMERA && pub.track is LocalVideoTrack) {
                        (pub.track as? LocalVideoTrack)?.startCapture()
                    }
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
                            track.startForegroundService(null, null)
                            track.startCapture()
                            publishVideoTrack(track, options = VideoTrackPublishOptions(null, screenShareTrackPublishDefaults))
                        }

                        else -> {
                            LKLog.w { "Attempting to enable an unknown source, ignoring." }
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

                        // Release camera session so other apps can use.
                        if (pub.source == Track.Source.CAMERA && track is LocalVideoTrack) {
                            track.stopCapture()
                        }
                    }
                }
            }
            return@withLock
        }
    }

    /**
     * Publishes an audio track.
     *
     * @param track The track to publish.
     * @param options The publish options to use, or [Room.audioTrackPublishDefaults] if none is passed.
     */
    suspend fun publishAudioTrack(
        track: LocalAudioTrack,
        options: AudioTrackPublishOptions = AudioTrackPublishOptions(
            null,
            audioTrackPublishDefaults,
        ),
        publishListener: PublishListener? = null,
    ) {
        val encodings = listOf(
            RtpParameters.Encoding(null, true, null).apply {
                if (options.audioBitrate != null && options.audioBitrate > 0) {
                    maxBitrateBps = options.audioBitrate
                }
            },
        )
        val publication = publishTrackImpl(
            track = track,
            options = options,
            requestConfig = {
                disableDtx = !options.dtx
                disableRed = !options.red
                source = options.source?.toProto() ?: LivekitModels.TrackSource.MICROPHONE
            },
            encodings = encodings,
            publishListener = publishListener,
        )

        if (publication != null) {
            val job = scope.launch {
                track::features.flow.collect {
                    engine.updateLocalAudioTrack(publication.sid, it)
                }
            }
            jobs[publication] = job
        }
    }

    /**
     * Publishes an video track.
     *
     * @param track The track to publish.
     * @param options The publish options to use, or [Room.videoTrackPublishDefaults] if none is passed.
     */
    suspend fun publishVideoTrack(
        track: LocalVideoTrack,
        options: VideoTrackPublishOptions = VideoTrackPublishOptions(null, videoTrackPublishDefaults),
        publishListener: PublishListener? = null,
    ) {
        val isSVC = isSVCCodec(options.videoCodec)

        @Suppress("NAME_SHADOWING") var options = options
        if (isSVC) {
            dynacast = true

            // Ensure backup codec and scalability for svc codecs.
            if (options.backupCodec == null) {
                options = options.copy(backupCodec = BackupVideoCodec())
            }
            if (options.scalabilityMode == null) {
                options = options.copy(scalabilityMode = "L3T3_KEY")
            }
        }
        val encodings = computeVideoEncodings(track.dimensions, options)
        val videoLayers =
            EncodingUtils.videoLayersFromEncodings(track.dimensions.width, track.dimensions.height, encodings, isSVC)

        publishTrackImpl(
            track = track,
            options = options,
            requestConfig = {
                width = track.dimensions.width
                height = track.dimensions.height
                source = options.source?.toProto() ?: if (track.options.isScreencast) {
                    LivekitModels.TrackSource.SCREEN_SHARE
                } else {
                    LivekitModels.TrackSource.CAMERA
                }
                addAllLayers(videoLayers)

                addSimulcastCodecs(
                    with(SimulcastCodec.newBuilder()) {
                        codec = options.videoCodec
                        cid = track.rtcTrack.id()
                        build()
                    },
                )
                // set up backup codec
                if (options.backupCodec?.codec != null && options.videoCodec != options.backupCodec?.codec) {
                    addSimulcastCodecs(
                        with(SimulcastCodec.newBuilder()) {
                            codec = options.backupCodec!!.codec
                            cid = ""
                            build()
                        },
                    )
                }
            },
            encodings = encodings,
            publishListener = publishListener,
        )
    }

    /**
     * @return true if the track publish was successful.
     */
    private suspend fun publishTrackImpl(
        track: Track,
        options: TrackPublishOptions,
        requestConfig: AddTrackRequest.Builder.() -> Unit,
        encodings: List<RtpParameters.Encoding> = emptyList(),
        publishListener: PublishListener? = null,
    ): LocalTrackPublication? {
        @Suppress("NAME_SHADOWING") var options = options

        @Suppress("NAME_SHADOWING") var encodings = encodings

        if (localTrackPublications.any { it.track == track }) {
            publishListener?.onPublishFailure(TrackException.PublishException("Track has already been published"))
            return null
        }

        val cid = track.rtcTrack.id()
        val builder = AddTrackRequest.newBuilder().apply {
            this.requestConfig()
        }
        val trackInfo = engine.addTrack(
            cid = cid,
            name = options.name ?: track.name,
            kind = track.kind.toProto(),
            stream = options.stream,
            builder = builder,
        )

        if (options is VideoTrackPublishOptions) {
            // server might not support the codec the client has requested, in that case, fallback
            // to a supported codec
            val primaryCodecMime = trackInfo.codecsList.firstOrNull()?.mimeType

            if (primaryCodecMime != null) {
                val updatedCodec = primaryCodecMime.mimeTypeToVideoCodec()
                if (updatedCodec != null && updatedCodec != options.videoCodec) {
                    LKLog.d { "falling back to server selected codec: $updatedCodec" }
                    options = options.copy(videoCodec = updatedCodec)

                    // recompute encodings since bitrates/etc could have changed
                    encodings = computeVideoEncodings((track as LocalVideoTrack).dimensions, options)
                }
            }
        }

        val transInit = RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid.value),
            encodings,
        )
        val transceiver = engine.createSenderTransceiver(track.rtcTrack, transInit)

        when (track) {
            is LocalVideoTrack -> track.transceiver = transceiver
            is LocalAudioTrack -> track.transceiver = transceiver
            else -> {
                throw IllegalArgumentException("Trying to publish a non local track of type ${track.javaClass}")
            }
        }

        if (transceiver == null) {
            publishListener?.onPublishFailure(TrackException.PublishException("null sender returned from peer connection"))
            return null
        }

        track.statsGetter = engine.createStatsGetter(transceiver.sender)

        // Handle trackBitrates
        if (encodings.isNotEmpty()) {
            if (options is VideoTrackPublishOptions && isSVCCodec(options.videoCodec) && encodings.firstOrNull()?.maxBitrateBps != null) {
                engine.registerTrackBitrateInfo(
                    cid = cid,
                    TrackBitrateInfo(
                        codec = options.videoCodec,
                        maxBitrate = (encodings.first().maxBitrateBps?.div(1000) ?: 0).toLong(),
                    ),
                )
            }
        }

        if (options is VideoTrackPublishOptions) {
            // Set preferred video codec order
            transceiver.sortVideoCodecPreferences(options.videoCodec, capabilitiesGetter)
            (track as LocalVideoTrack).codec = options.videoCodec

            val rtpParameters = transceiver.sender.parameters
            rtpParameters.degradationPreference = options.degradationPreference
            transceiver.sender.parameters = rtpParameters
        }

        val publication = LocalTrackPublication(
            info = trackInfo,
            track = track,
            participant = this,
            options = options,
        )
        addTrackPublication(publication)
        LKLog.v { "add track publication $publication" }

        publishListener?.onPublishSuccess(publication)
        internalListener?.onTrackPublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackPublished(this, publication), scope)

        return publication
    }

    private fun computeVideoEncodings(
        dimensions: Track.Dimensions,
        options: VideoTrackPublishOptions,
    ): List<RtpParameters.Encoding> {
        val (width, height) = dimensions
        var encoding = options.videoEncoding
        val simulcast = options.simulcast
        val scalabilityMode = options.scalabilityMode

        if ((encoding == null && !simulcast) || width == 0 || height == 0) {
            return emptyList()
        }

        if (encoding == null) {
            encoding = EncodingUtils.determineAppropriateEncoding(width, height)
            LKLog.d { "using video encoding: $encoding" }
        }

        val encodings = mutableListOf<RtpParameters.Encoding>()

        if (scalabilityMode != null && isSVCCodec(options.videoCodec)) {
            val rtpEncoding = encoding.toRtpEncoding()
            rtpEncoding.scalabilityMode = scalabilityMode
            encodings.add(rtpEncoding)
            return encodings
        } else if (simulcast) {
            val presets = EncodingUtils.presetsForResolution(width, height)
            val midPreset = presets[1]
            val lowPreset = presets[0]

            fun addEncoding(videoEncoding: VideoEncoding, scale: Double) {
                if (scale < 1.0) {
                    LKLog.w { "Discarding encoding with a scale < 1.0: $scale." }
                    return
                }
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

            fun calculateScaleDown(captureParam: VideoCaptureParameter): Double {
                val targetSize = max(captureParam.width, captureParam.height)
                return size / targetSize.toDouble()
            }
            if (size >= 960) {
                val lowScale = calculateScaleDown(lowPreset.capture)
                val midScale = calculateScaleDown(midPreset.capture)

                addEncoding(lowPreset.encoding, lowScale)
                addEncoding(midPreset.encoding, midScale)
            } else {
                val lowScale = calculateScaleDown(lowPreset.capture)
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

    private fun computeTrackBackupOptionsAndEncodings(
        track: LocalVideoTrack,
        videoCodec: VideoCodec,
        options: VideoTrackPublishOptions,
    ): Pair<VideoTrackPublishOptions, List<RtpParameters.Encoding>>? {
        if (!options.hasBackupCodec()) {
            return null
        }

        if (videoCodec.codecName != options.backupCodec?.codec) {
            LKLog.w { "Server requested different codec than specified backup. server: $videoCodec, specified: ${options.backupCodec?.codec}" }
        }

        val backupOptions = options.copy(
            videoCodec = videoCodec.codecName,
            videoEncoding = options.backupCodec!!.encoding,
        )
        val backupEncodings = computeVideoEncodings(track.dimensions, backupOptions)
        return backupOptions to backupEncodings
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
        participantTrackPermissions: List<ParticipantTrackPermission> = emptyList(),
    ) {
        engine.updateSubscriptionPermissions(allParticipantsAllowed, participantTrackPermissions)
    }

    /**
     * Unpublish a track.
     *
     * @param stopOnUnpublish if true, stops the track after unpublishing the track. Defaults to true.
     */
    fun unpublishTrack(track: Track, stopOnUnpublish: Boolean = true) {
        val publication = localTrackPublications.firstOrNull { it.track == track }
        if (publication === null) {
            LKLog.d { "this track was never published." }
            return
        }

        val publicationJob = jobs[publication]
        if (publicationJob != null) {
            publicationJob.cancel()
            jobs.remove(publicationJob)
        }

        val sid = publication.sid
        trackPublications = trackPublications.toMutableMap().apply { remove(sid) }

        if (engine.connectionState == ConnectionState.CONNECTED) {
            engine.removeTrack(track.rtcTrack)
        }
        if (stopOnUnpublish) {
            track.stop()
        }
        internalListener?.onTrackUnpublished(publication, this)
        eventBus.postEvent(ParticipantEvent.LocalTrackUnpublished(this, publication), scope)
    }

    /**
     * Publish a new data payload to the room. Data will be forwarded to each participant in the room.
     * Each payload must not exceed 15k in size
     *
     * @param data payload to send
     * @param reliability for delivery guarantee, use RELIABLE. for fastest delivery without guarantee, use LOSSY
     * @param topic the topic under which the message was published
     * @param identities list of participant identities to deliver the payload, null to deliver to everyone
     */
    @Suppress("unused")
    suspend fun publishData(
        data: ByteArray,
        reliability: DataPublishReliability = DataPublishReliability.RELIABLE,
        topic: String? = null,
        identities: List<Identity>? = null,
    ) {
        if (data.size > RTCEngine.MAX_DATA_PACKET_SIZE) {
            throw IllegalArgumentException("cannot publish data larger than " + RTCEngine.MAX_DATA_PACKET_SIZE)
        }

        val kind = when (reliability) {
            DataPublishReliability.RELIABLE -> LivekitModels.DataPacket.Kind.RELIABLE
            DataPublishReliability.LOSSY -> LivekitModels.DataPacket.Kind.LOSSY
        }
        val packetBuilder = LivekitModels.UserPacket.newBuilder().apply {
            payload = ByteString.copyFrom(data)
            participantSid = sid.value
            if (topic != null) {
                setTopic(topic)
            }
            if (identities != null) {
                addAllDestinationIdentities(identities.map { it.value })
            }
        }
        val dataPacket = LivekitModels.DataPacket.newBuilder()
            .setUser(packetBuilder)
            .setKind(kind)
            .build()

        engine.sendData(dataPacket)
    }

    /**
     * @suppress
     */
    @VisibleForTesting
    override fun updateFromInfo(info: LivekitModels.ParticipantInfo) {
        super.updateFromInfo(info)

        // detect tracks that have mute status mismatched on server
        for (ti in info.tracksList) {
            val publication = this.trackPublications[ti.sid] as? LocalTrackPublication ?: continue
            val localMuted = publication.muted
            if (ti.muted != localMuted) {
                engine.updateMuteStatus(sid.value, localMuted)
            }
        }
    }

    /**
     * Updates the metadata of the local participant.  Changes will not be reflected until the
     * server responds confirming the update.
     * Note: this requires `CanUpdateOwnMetadata` permission encoded in the token.
     * @param metadata
     */
    fun updateMetadata(metadata: String) {
        this.engine.client.sendUpdateLocalMetadata(metadata, name)
    }

    /**
     * Updates the name of the local participant. Changes will not be reflected until the
     * server responds confirming the update.
     * Note: this requires `CanUpdateOwnMetadata` permission encoded in the token.
     * @param name
     */
    fun updateName(name: String) {
        this.engine.client.sendUpdateLocalMetadata(metadata, name)
    }

    /**
     * Set or update participant attributes. It will make updates only to keys that
     * are present in [attributes], and will not override others.
     *
     * To delete a value, set the value to an empty string.
     *
     * Note: this requires `canUpdateOwnMetadata` permission.
     * @param attributes attributes to update
     */
    fun updateAttributes(attributes: Map<String, String>) {
        this.engine.client.sendUpdateLocalMetadata(metadata, name, attributes)
    }

    internal fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        val pub = trackPublications[trackSid]
        pub?.muted = muted
    }

    internal fun handleSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        if (!dynacast) {
            return
        }

        val trackSid = subscribedQualityUpdate.trackSid
        val subscribedCodecs = subscribedQualityUpdate.subscribedCodecsList
        val qualities = subscribedQualityUpdate.subscribedQualitiesList
        val pub = trackPublications[trackSid] as? LocalTrackPublication ?: return
        val track = pub.track as? LocalVideoTrack ?: return
        val options = pub.options as? VideoTrackPublishOptions ?: return

        if (subscribedCodecs.isNotEmpty()) {
            val newCodecs = track.setPublishingCodecs(subscribedCodecs)
            for (codec in newCodecs) {
                if (isBackupCodec(codec.codecName)) {
                    LKLog.d { "publish $codec for $trackSid" }
                    publishAdditionalCodecForTrack(track, codec, options)
                }
            }
        }
        if (qualities.isNotEmpty()) {
            track.setPublishingLayers(qualities)
        }
    }

    private fun publishAdditionalCodecForTrack(track: LocalVideoTrack, codec: VideoCodec, options: VideoTrackPublishOptions) {
        val existingPublication = trackPublications[track.sid] ?: run {
            LKLog.w { "attempting to publish additional codec for non-published track?!" }
            return
        }

        val result = computeTrackBackupOptionsAndEncodings(track, codec, options) ?: run {
            LKLog.i { "backup codec has been disabled, ignoring request to add additional codec for track" }
            return
        }
        val (newOptions, newEncodings) = result
        val simulcastTrack = track.addSimulcastTrack(codec, newEncodings)

        val transceiverInit = RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(this.sid.value),
            newEncodings,
        )

        scope.launch {
            val transceiver = engine.createSenderTransceiver(track.rtcTrack, transceiverInit)
            if (transceiver == null) {
                LKLog.w { "couldn't create new transceiver! $codec" }
                return@launch
            }
            transceiver.sortVideoCodecPreferences(newOptions.videoCodec, capabilitiesGetter)
            simulcastTrack.sender = transceiver.sender

            val trackRequest = AddTrackRequest.newBuilder().apply {
                cid = transceiver.sender.id()
                sid = existingPublication.sid
                type = track.kind.toProto()
                muted = !track.enabled
                source = existingPublication.source.toProto()
                addSimulcastCodecs(
                    with(SimulcastCodec.newBuilder()) {
                        this@with.codec = codec.codecName
                        this@with.cid = transceiver.sender.id()
                        build()
                    },
                )
                addAllLayers(
                    EncodingUtils.videoLayersFromEncodings(
                        track.dimensions.width,
                        track.dimensions.height,
                        newEncodings,
                        isSVCCodec(codec.codecName),
                    ),
                )
            }

            val trackInfo = engine.addTrack(
                cid = simulcastTrack.rtcTrack.id(),
                name = existingPublication.name,
                kind = existingPublication.kind.toProto(),
                stream = options.stream,
                builder = trackRequest,
            )

            engine.negotiatePublisher()

            LKLog.d { "published $codec for track ${track.sid}, $trackInfo" }
        }
    }

    internal fun handleLocalTrackUnpublished(unpublishedResponse: LivekitRtc.TrackUnpublishedResponse) {
        val pub = trackPublications[unpublishedResponse.trackSid]
        val track = pub?.track
        if (track == null) {
            LKLog.w { "Received unpublished track response for unknown or non-published track: ${unpublishedResponse.trackSid}" }
            return
        }

        unpublishTrack(track)
    }

    internal fun prepareForFullReconnect() {
        val pubs = localTrackPublications.toList() // creates a copy, so is safe from the following removal.

        // Only set the first time we start a full reconnect.
        if (republishes == null) {
            republishes = pubs
        }

        trackPublications = trackPublications.toMutableMap().apply { clear() }

        for (publication in pubs) {
            internalListener?.onTrackUnpublished(publication, this)
            eventBus.postEvent(ParticipantEvent.LocalTrackUnpublished(this, publication), scope)
        }
    }

    internal suspend fun republishTracks() {
        val publish = republishes?.toList() ?: emptyList()
        republishes = null

        for (pub in publish) {
            val track = pub.track ?: continue
            unpublishTrack(track, false)
            // Cannot publish muted tracks.
            if (!pub.muted) {
                when (track) {
                    is LocalAudioTrack -> publishAudioTrack(track, pub.options as AudioTrackPublishOptions, null)
                    is LocalVideoTrack -> publishVideoTrack(track, pub.options as VideoTrackPublishOptions, null)
                    else -> throw IllegalStateException("LocalParticipant has a non local track publish?")
                }
            }
        }
    }

    internal fun onLocalTrackSubscribed(publication: LocalTrackPublication) {
        if (!trackPublications.containsKey(publication.sid)) {
            LKLog.w { "Could not find local track publication for subscribed event " }
            return
        }

        eventBus.postEvent(ParticipantEvent.LocalTrackSubscribed(this, publication), scope)
    }

    /**
     * @suppress
     */
    fun cleanup() {
        for (pub in trackPublications.values) {
            val track = pub.track

            if (track != null) {
                track.stop()
                unpublishTrack(track, stopOnUnpublish = false)

                // We have the original track object reference, meaning we own it. Dispose here.
                try {
                    track.dispose()
                } catch (e: Exception) {
                    LKLog.d(e) { "Exception thrown when cleaning up local participant track $pub:" }
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun dispose() {
        cleanup()
        super.dispose()
    }

    interface PublishListener {
        fun onPublishSuccess(publication: TrackPublication) {}
        fun onPublishFailure(exception: Exception) {}
    }

    @AssistedFactory
    interface Factory {
        fun create(dynacast: Boolean): LocalParticipant
    }
}

internal fun LocalParticipant.publishTracksInfo(): List<LivekitRtc.TrackPublishedResponse> {
    return trackPublications.values.mapNotNull { trackPub ->
        val track = trackPub.track ?: return@mapNotNull null

        LivekitRtc.TrackPublishedResponse.newBuilder()
            .setCid(track.rtcTrack.id())
            .setTrack(trackPub.trackInfo)
            .build()
    }
}

interface TrackPublishOptions {
    /**
     * The name of the track.
     */
    val name: String?

    /**
     * The source of a track, camera, microphone or screen.
     */
    val source: Track.Source?

    /**
     * The stream name for the track. Audio and video tracks with the same stream
     * name will be placed in the same `MediaStream` and offer better synchronization.
     *
     * By default, camera and microphone will be placed in the same stream.
     */
    val stream: String?
}

abstract class BaseVideoTrackPublishOptions {
    abstract val videoEncoding: VideoEncoding?
    abstract val simulcast: Boolean

    /**
     * The video codec to use if available.
     *
     * Defaults to VP8.
     *
     * @see [VideoCodec]
     */
    abstract val videoCodec: String

    /**
     * scalability mode for svc codecs, defaults to 'L3T3'.
     * for svc codecs, simulcast is disabled.
     */
    abstract val scalabilityMode: String?

    /**
     * Multi-codec Simulcast
     *
     * Codecs such as VP9 and AV1 are not supported by all clients. When backupCodec is
     * set, when an incompatible client attempts to subscribe to the track, LiveKit
     * will automatically publish a secondary track encoded with the backup codec.
     */
    abstract val backupCodec: BackupVideoCodec?

    /**
     * When bandwidth is constrained, this preference indicates which is preferred
     * between degrading resolution vs. framerate.
     *
     * null value indicates default value (maintain framerate).
     */
    abstract val degradationPreference: RtpParameters.DegradationPreference?
}

data class VideoTrackPublishDefaults(
    override val videoEncoding: VideoEncoding? = null,
    override val simulcast: Boolean = true,
    override val videoCodec: String = VideoCodec.VP8.codecName,
    override val scalabilityMode: String? = null,
    override val backupCodec: BackupVideoCodec? = null,
    override val degradationPreference: RtpParameters.DegradationPreference? = null,
) : BaseVideoTrackPublishOptions()

data class VideoTrackPublishOptions(
    override val name: String? = null,
    override val videoEncoding: VideoEncoding? = null,
    override val simulcast: Boolean = true,
    override val videoCodec: String = VideoCodec.VP8.codecName,
    override val scalabilityMode: String? = null,
    override val backupCodec: BackupVideoCodec? = null,
    override val source: Track.Source? = null,
    override val stream: String? = null,
    override val degradationPreference: RtpParameters.DegradationPreference? = null,
) : BaseVideoTrackPublishOptions(), TrackPublishOptions {
    constructor(
        name: String? = null,
        base: BaseVideoTrackPublishOptions,
        source: Track.Source? = null,
        stream: String? = null,
    ) : this(
        name = name,
        videoEncoding = base.videoEncoding,
        simulcast = base.simulcast,
        videoCodec = base.videoCodec,
        scalabilityMode = base.scalabilityMode,
        backupCodec = base.backupCodec,
        source = source,
        stream = stream,
        degradationPreference = base.degradationPreference,
    )

    fun createBackupOptions(): VideoTrackPublishOptions? {
        return if (hasBackupCodec()) {
            copy(
                videoCodec = backupCodec!!.codec,
                videoEncoding = backupCodec.encoding,
            )
        } else {
            null
        }
    }
}

data class BackupVideoCodec(
    val codec: String = "vp8",
    val encoding: VideoEncoding? = null,
    val simulcast: Boolean = true,
)

abstract class BaseAudioTrackPublishOptions {
    /**
     * The target audioBitrate to use.
     */
    abstract val audioBitrate: Int?

    /**
     * dtx (Discontinuous Transmission of audio), enabled by default for mono tracks.
     */
    abstract val dtx: Boolean

    /**
     * red (Redundant Audio Data), enabled by default for mono tracks.
     */
    abstract val red: Boolean
}

enum class AudioPresets(
    val maxBitrate: Int,
) {
    TELEPHONE(12_000),
    SPEECH(24_000),
    MUSIC(48_000),
    MUSIC_STEREO(64_000),
    MUSIC_HIGH_QUALITY(96_000),
    MUSIC_HIGH_QUALITY_STEREO(128_000)
}

/**
 * Default options for publishing an audio track.
 */
data class AudioTrackPublishDefaults(
    override val audioBitrate: Int? = AudioPresets.MUSIC.maxBitrate,
    override val dtx: Boolean = true,
    override val red: Boolean = true,
) : BaseAudioTrackPublishOptions()

/**
 * Options for publishing an audio track.
 */
data class AudioTrackPublishOptions(
    override val name: String? = null,
    override val audioBitrate: Int? = null,
    override val dtx: Boolean = true,
    override val red: Boolean = true,
    override val source: Track.Source? = null,
    override val stream: String? = null,
) : BaseAudioTrackPublishOptions(), TrackPublishOptions {
    constructor(
        name: String? = null,
        base: BaseAudioTrackPublishOptions,
        source: Track.Source? = null,
        stream: String? = null,
    ) : this(
        name = name,
        audioBitrate = base.audioBitrate,
        dtx = base.dtx,
        red = base.red,
        source = source,
        stream = stream,
    )
}

data class ParticipantTrackPermission(
    /**
     * The participant identity this permission applies to.
     * You can either provide this or `participantSid`
     */
    val participantIdentity: String? = null,
    /**
     * The participant id this permission applies to.
     */
    val participantSid: String? = null,
    /**
     * If set to true, the target participant can subscribe to all tracks from the local participant.
     *
     * Takes precedence over [allowedTrackSids].
     */
    val allTracksAllowed: Boolean = false,
    /**
     * The list of track ids that the target participant can subscribe to.
     */
    val allowedTrackSids: List<String> = emptyList(),
) {
    init {
        if (participantIdentity == null && participantSid == null) {
            throw IllegalArgumentException("Either identity or sid must be provided.")
        }
    }

    internal fun toProto(): LivekitRtc.TrackPermission {
        return LivekitRtc.TrackPermission.newBuilder()
            .setParticipantIdentity(participantIdentity)
            .setParticipantSid(participantSid)
            .setAllTracks(allTracksAllowed)
            .addAllTrackSids(allowedTrackSids)
            .build()
    }
}

internal fun VideoTrackPublishOptions.hasBackupCodec(): Boolean {
    return backupCodec?.codec != null && videoCodec != backupCodec.codec
}

private val backupCodecs = listOf(VideoCodec.VP8.codecName, VideoCodec.H264.codecName)
private fun isBackupCodec(codecName: String) = backupCodecs.contains(codecName)
