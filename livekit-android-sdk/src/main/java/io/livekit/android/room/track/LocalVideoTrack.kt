/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.room.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.memory.CloseableManager
import io.livekit.android.memory.SurfaceTextureHelperCloser
import io.livekit.android.room.DefaultsManager
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.createCameraEnumerator
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraCapturerUtils.getCameraPosition
import io.livekit.android.room.track.video.CameraCapturerWithSize
import io.livekit.android.room.track.video.VideoCapturerWithSize
import io.livekit.android.room.util.EncodingUtils
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flowDelegate
import livekit.LivekitModels
import livekit.LivekitModels.VideoQuality
import livekit.LivekitRtc
import livekit.LivekitRtc.SubscribedCodec
import org.webrtc.CameraVideoCapturer
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoProcessor
import org.webrtc.VideoSource
import java.util.UUID

/**
 * A representation of a local video track (generally input coming from camera or screen).
 *
 * [startCapture] should be called before use.
 */
open class LocalVideoTrack
@AssistedInject
constructor(
    @Assisted private var capturer: VideoCapturer,
    @Assisted private var source: VideoSource,
    @Assisted name: String,
    @Assisted options: LocalVideoTrackOptions,
    @Assisted rtcTrack: org.webrtc.VideoTrack,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val context: Context,
    private val eglBase: EglBase,
    private val defaultsManager: DefaultsManager,
    private val trackFactory: Factory,
) : VideoTrack(name, rtcTrack) {

    override var rtcTrack: org.webrtc.VideoTrack = rtcTrack
        internal set

    internal var codec: String? = null
    private var subscribedCodecs: List<SubscribedCodec>? = null
    private val simulcastCodecs = mutableMapOf<VideoCodec, SimulcastTrackInfo>()

    @FlowObservable
    @get:FlowObservable
    var options: LocalVideoTrackOptions by flowDelegate(options)

    val dimensions: Dimensions
        get() {
            (capturer as? VideoCapturerWithSize)?.let { capturerWithSize ->
                val size = capturerWithSize.findCaptureFormat(
                    options.captureParams.width,
                    options.captureParams.height,
                )
                return Dimensions(size.width, size.height)
            }
            return Dimensions(options.captureParams.width, options.captureParams.height)
        }

    internal var transceiver: RtpTransceiver? = null
    internal val sender: RtpSender?
        get() = transceiver?.sender

    private val closeableManager = CloseableManager()

    open fun startCapture() {
        capturer.startCapture(
            options.captureParams.width,
            options.captureParams.height,
            options.captureParams.maxFps,
        )
    }

    open fun stopCapture() {
        capturer.stopCapture()
    }

    override fun stop() {
        capturer.stopCapture()
        super.stop()
    }

    override fun dispose() {
        super.dispose()
        capturer.dispose()
        closeableManager.close()
    }

    fun setDeviceId(deviceId: String) {
        restartTrack(options.copy(deviceId = deviceId))
    }

    /**
     * Switch to a different camera. Only works if this track is backed by a camera capturer.
     *
     * If neither deviceId or position is provided, or the specified camera cannot be found,
     * this will switch to the next camera, if one is available.
     */
    fun switchCamera(deviceId: String? = null, position: CameraPosition? = null) {
        val cameraCapturer = capturer as? CameraVideoCapturer ?: run {
            LKLog.w { "Attempting to switch camera on a non-camera video track!" }
            return
        }

        var targetDeviceId: String? = null
        val enumerator = createCameraEnumerator(context)
        if (deviceId != null || position != null) {
            targetDeviceId = enumerator.findCamera(deviceId, position, fallback = false)
        }

        if (targetDeviceId == null) {
            val deviceNames = enumerator.deviceNames
            if (deviceNames.size < 2) {
                LKLog.w { "No available cameras to switch to!" }
                return
            }
            val currentIndex = deviceNames.indexOf(options.deviceId)
            targetDeviceId = deviceNames[(currentIndex + 1) % deviceNames.size]
        }

        fun updateCameraOptions() {
            val newOptions = options.copy(
                deviceId = targetDeviceId,
                position = enumerator.getCameraPosition(targetDeviceId),
            )
            options = newOptions
        }

        val cameraSwitchHandler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                // For cameras we control, wait until the first frame to ensure everything is okay.
                if (cameraCapturer is CameraCapturerWithSize) {
                    cameraCapturer.cameraEventsDispatchHandler
                        .registerHandler(
                            object : CameraEventsHandler {
                                override fun onFirstFrameAvailable() {
                                    updateCameraOptions()
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraError(p0: String?) {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraDisconnected() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }

                                override fun onCameraFreezed(p0: String?) {
                                }

                                override fun onCameraOpening(p0: String?) {
                                }

                                override fun onCameraClosed() {
                                    cameraCapturer.cameraEventsDispatchHandler.unregisterHandler(this)
                                }
                            },
                        )
                } else {
                    updateCameraOptions()
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                LKLog.w { "switching camera failed: $errorDescription" }
            }
        }
        if (targetDeviceId == null) {
            LKLog.w { "No target camera found!" }
            return
        } else {
            cameraCapturer.switchCamera(cameraSwitchHandler, targetDeviceId)
        }
    }

    /**
     * Restart a track with new options.
     */
    fun restartTrack(options: LocalVideoTrackOptions = defaultsManager.videoTrackCaptureDefaults.copy()) {
        val oldCapturer = capturer
        val oldSource = source
        val oldRtcTrack = rtcTrack

        oldCapturer.stopCapture()
        oldCapturer.dispose()
        oldSource.dispose()

        oldRtcTrack.setEnabled(false)

        // We always own our copy of rtcTrack, so we need to dispose it.
        // Note: For the first rtcTrack we pass to the PeerConnection, PeerConnection actually
        // passes it down to the native, and then ends up holding onto a separate copy at the
        // Java layer. This means our initial rtcTrack isn't owned by PeerConnection, and is
        // our responsibility to dispose.
        oldRtcTrack.dispose()

        // Close resources associated to the old track. new track resources is registered in createTrack.
        val oldCloseable = closeableManager.unregisterResource(oldRtcTrack)
        oldCloseable?.close()

        val newTrack = createTrack(
            peerConnectionFactory,
            context,
            name,
            options,
            eglBase,
            trackFactory,
        )

        // migrate video sinks to the new track
        for (sink in sinks) {
            oldRtcTrack.removeSink(sink)
            newTrack.addRenderer(sink)
        }

        capturer = newTrack.capturer
        source = newTrack.source
        rtcTrack = newTrack.rtcTrack
        this.options = options
        startCapture()
        sender?.setTrack(newTrack.rtcTrack, false)
    }

    internal fun setPublishingLayers(
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        val sender = transceiver?.sender ?: return

        setPublishingLayersForSender(sender, qualities)
    }

    private fun setPublishingLayersForSender(
        sender: RtpSender,
        qualities: List<LivekitRtc.SubscribedQuality>,
    ) {
        val parameters = sender.parameters ?: return
        val encodings = parameters.encodings ?: return
        var hasChanged = false

        if (encodings.firstOrNull()?.scalabilityMode != null) {
            val encoding = encodings.first()
            var maxQuality = VideoQuality.OFF
            for (quality in qualities) {
                if (quality.enabled && (maxQuality == VideoQuality.OFF || quality.quality.number > maxQuality.number)) {
                    maxQuality = quality.quality
                }
            }

            if (maxQuality == VideoQuality.OFF) {
                if (encoding.active) {
                    LKLog.v { "setting svc track to disabled" }
                    encoding.active = false
                    hasChanged = true
                }
            } else if (!encoding.active) {
                LKLog.v { "setting svc track to enabled" }
                encoding.active = true
                hasChanged = true
            }
        } else {
            // simulcast dynacast encodings
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
        }

        if (hasChanged) {
            // This refeshes the native code with the new information
            sender.parameters = sender.parameters
        }
    }

    fun setPublishingCodecs(codecs: List<SubscribedCodec>): List<VideoCodec> {
        LKLog.v { "setting publishing codecs: $codecs" }

        // only enable simulcast codec for preferred codec set
        if (this.codec == null && codecs.isNotEmpty()) {
            setPublishingLayers(codecs.first().qualitiesList)
            return emptyList()
        }

        this.subscribedCodecs = codecs

        val newCodecs = mutableListOf<VideoCodec>()

        for (codec in codecs) {
            if (this.codec == codec.codec) {
                setPublishingLayers(codec.qualitiesList)
            } else {
                val videoCodec = try {
                    VideoCodec.fromCodecName(codec.codec)
                } catch (e: Exception) {
                    LKLog.w { "unknown publishing codec ${codec.codec}!" }
                    continue
                }

                LKLog.d { "try setPublishingCodec for ${codec.codec}" }
                val simulcastInfo = this.simulcastCodecs[videoCodec]
                if (simulcastInfo?.sender == null) {
                    for (q in codec.qualitiesList) {
                        if (q.enabled) {
                            newCodecs.add(videoCodec)
                            break
                        }
                    }
                } else {
                    LKLog.d { "try setPublishingLayersForSender ${codec.codec}" }
                    setPublishingLayersForSender(
                        simulcastInfo.sender!!,
                        codec.qualitiesList,
                    )
                }
            }
        }
        return newCodecs
    }

    internal fun addSimulcastTrack(codec: VideoCodec, encodings: List<RtpParameters.Encoding>): SimulcastTrackInfo {
        if (this.simulcastCodecs.containsKey(codec)) {
            throw IllegalStateException("$codec already added!")
        }
        val simulcastTrackInfo = SimulcastTrackInfo(
            codec = codec.codecName,
            rtcTrack = rtcTrack,
            encodings = encodings,
        )
        simulcastCodecs[codec] = simulcastTrackInfo
        return simulcastTrackInfo
    }

    @AssistedFactory
    interface Factory {
        fun create(
            capturer: VideoCapturer,
            source: VideoSource,
            name: String,
            options: LocalVideoTrackOptions,
            rtcTrack: org.webrtc.VideoTrack,
        ): LocalVideoTrack
    }

    companion object {

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            capturer: VideoCapturer,
            options: LocalVideoTrackOptions = LocalVideoTrackOptions(),
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            val source = peerConnectionFactory.createVideoSource(false)
            source.setVideoProcessor(videoProcessor)
            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)
            capturer.initialize(
                surfaceTextureHelper,
                context,
                source.capturerObserver,
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = options,
                name = name,
                rtcTrack = rtcTrack,
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper),
            )
            return track
        }

        internal fun createTrack(
            peerConnectionFactory: PeerConnectionFactory,
            context: Context,
            name: String,
            options: LocalVideoTrackOptions,
            rootEglBase: EglBase,
            trackFactory: Factory,
            videoProcessor: VideoProcessor? = null,
        ): LocalVideoTrack {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Camera permissions are required to create a camera video track.")
            }

            val source = peerConnectionFactory.createVideoSource(options.isScreencast)
            source.setVideoProcessor(videoProcessor)
            val (capturer, newOptions) = CameraCapturerUtils.createCameraCapturer(context, options) ?: TODO()
            val surfaceTextureHelper = SurfaceTextureHelper.create("VideoCaptureThread", rootEglBase.eglBaseContext)
            capturer.initialize(
                surfaceTextureHelper,
                context,
                source.capturerObserver,
            )
            val rtcTrack = peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), source)

            val track = trackFactory.create(
                capturer = capturer,
                source = source,
                options = newOptions,
                name = name,
                rtcTrack = rtcTrack,
            )

            track.closeableManager.registerResource(
                rtcTrack,
                SurfaceTextureHelperCloser(surfaceTextureHelper),
            )

            return track
        }
    }
}

internal data class SimulcastTrackInfo(
    var codec: String,
    var rtcTrack: MediaStreamTrack,
    var sender: RtpSender? = null,
    var encodings: List<RtpParameters.Encoding>? = null,
)
