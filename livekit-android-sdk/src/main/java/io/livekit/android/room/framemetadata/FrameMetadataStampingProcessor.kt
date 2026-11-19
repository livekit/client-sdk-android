/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.framemetadata

import io.livekit.android.room.track.FrameMetadata
import io.livekit.android.room.track.FrameMetadataProvider
import io.livekit.android.room.track.FrameMetadataPublishOptions
import io.livekit.android.room.track.toRtc
import io.livekit.android.room.track.toSdk
import io.livekit.android.util.LKLog
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink

/**
 * The last step of a local video track's processing chain. It wraps whatever processor the track
 * was created with, so it sees each frame after every other processor, and sets the frame metadata
 * from [state] on it.
 *
 * It's installed when the track is created and does nothing until [state] is armed at publish.
 */
internal class FrameMetadataStampingProcessor(
    private val inner: VideoProcessor?,
) : VideoProcessor {

    @Volatile
    var state = FrameMetadataStampState()

    @Volatile
    private var sink: VideoSink? = null

    private val stampingSink = VideoSink { frame -> deliver(frame) }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
        inner?.setSink(if (sink != null) stampingSink else null)
    }

    override fun onCapturerStarted(success: Boolean) {
        inner?.onCapturerStarted(success)
    }

    override fun onCapturerStopped() {
        inner?.onCapturerStopped()
    }

    // Forwards the two-argument version, which some processors override (see NoDropVideoProcessor).
    override fun onFrameCaptured(frame: VideoFrame, parameters: VideoProcessor.FrameAdaptationParameters) {
        val inner = inner
        if (inner != null) {
            inner.onFrameCaptured(frame, parameters)
            return
        }
        val adaptedFrame = VideoProcessor.applyFrameAdaptationParameters(frame, parameters) ?: return
        deliver(adaptedFrame)
        adaptedFrame.release()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val inner = inner
        if (inner != null) {
            inner.onFrameCaptured(frame)
        } else {
            deliver(frame)
        }
    }

    private fun deliver(frame: VideoFrame) {
        val sink = this.sink ?: return
        // A new frame shares the buffer without retaining it, so only the original is released.
        sink.onFrame(state.stamp(frame))
    }
}

/**
 * The frame metadata settings of one local video track: whether it's armed, the frame ID counter
 * and the app's [FrameMetadataProvider]. It belongs to the track, so it carries over when the
 * track's source and processor are replaced.
 */
internal class FrameMetadataStampState(
    // WebRTC's clock, which is CLOCK_MONOTONIC on Android, the same as System.nanoTime(). Not
    // TimestampAligner.getRtcTimeNanos(): it creates a native Environment on every call and never
    // frees it.
    private val rtcTimeNanos: () -> Long = { System.nanoTime() },
    private val wallTimeMillis: () -> Long = { System.currentTimeMillis() },
    initialFrameId: Long = 1L,
) {
    @Volatile
    var provider: FrameMetadataProvider? = null

    @Volatile
    private var options: FrameMetadataPublishOptions? = null

    private val frameIdLock = Any()
    private var nextFrameId = initialFrameId

    @Volatile
    private var loggedProviderError = false

    val isArmed: Boolean
        get() = options != null

    fun arm(options: FrameMetadataPublishOptions) {
        this.options = options
    }

    fun disarm() {
        options = null
    }

    /**
     * Returns [frame] if there's nothing to change, or a new frame with the metadata to send.
     *
     * Starts from any metadata already on the frame, fills fields that are 0 with the defaults
     * turned on in the options, then lets the provider replace the result.
     */
    fun stamp(frame: VideoFrame): VideoFrame {
        val options = this.options ?: return frame
        val existing = frame.frameMetadata?.toSdk()
        val withDefaults = fillDefaults(frame, options, existing ?: FrameMetadata())
        return frameWith(frame, existing, applyProvider(frame, withDefaults))
    }

    private fun fillDefaults(frame: VideoFrame, options: FrameMetadataPublishOptions, metadata: FrameMetadata): FrameMetadata {
        val timestampUs = if (options.timestamp && metadata.userTimestampUs == 0L) {
            defaultTimestampUs(frame)
        } else {
            metadata.userTimestampUs
        }
        val frameId = if (options.frameId && metadata.frameId == 0L) takeFrameId() else metadata.frameId
        return metadata.copy(userTimestampUs = timestampUs, frameId = frameId)
    }

    private fun applyProvider(frame: VideoFrame, current: FrameMetadata): FrameMetadata {
        val provider = this.provider ?: return current
        return try {
            provider.provide(frame, current) ?: current
        } catch (e: Exception) {
            if (!loggedProviderError) {
                loggedProviderError = true
                LKLog.w(e) { "FrameMetadataProvider threw; sending the values it was given. Logged once." }
            }
            current
        }
    }

    private fun frameWith(frame: VideoFrame, existing: FrameMetadata?, result: FrameMetadata): VideoFrame {
        val isEmpty = result.userTimestampUs == 0L && result.frameId == 0L && result.userData?.isEmpty() != false
        return when {
            isEmpty && existing == null -> frame
            // Drop the metadata a processor set, since nothing is left to send.
            isEmpty -> VideoFrame(frame.buffer, frame.rotation, frame.timestampNs)
            result == existing -> frame
            else -> VideoFrame(frame.buffer, frame.rotation, frame.timestampNs, result.toRtc())
        }
    }

    // The frame's capture time, moved from WebRTC's monotonic clock to wall-clock time.
    private fun defaultTimestampUs(frame: VideoFrame): Long {
        val offsetNanos = wallTimeMillis() * 1_000_000L - rtcTimeNanos()
        val timestampUs = (frame.timestampNs + offsetNanos) / 1_000L
        return if (timestampUs > 0) timestampUs else 0L
    }

    // A uint32 that starts at 1 and wraps back to 1, since 0 means "not sent".
    private fun takeFrameId(): Long = synchronized(frameIdLock) {
        val id = nextFrameId
        nextFrameId = if (id >= MAX_FRAME_ID) 1L else id + 1
        id
    }

    companion object {
        const val MAX_FRAME_ID = 0xFFFFFFFFL
    }
}
