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
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import livekit.org.webrtc.FrameMetadata as RtcFrameMetadata

@RunWith(RobolectricTestRunner::class)
class FrameMetadataStampingTest {

    private val buffer = Mockito.mock(VideoFrame.Buffer::class.java)

    private fun frame(timestampNs: Long = 4_000_000_000L, metadata: RtcFrameMetadata? = null) =
        VideoFrame(buffer, 0, timestampNs, metadata)

    // WebRTC's clock at 5 s and the wall clock at 1,700,000,000,000 ms, so a frame captured at
    // 4 s on WebRTC's clock is 1 s before the wall-clock time.
    private fun state(initialFrameId: Long = 1L) = FrameMetadataStampState(
        rtcTimeNanos = { 5_000_000_000L },
        wallTimeMillis = { 1_700_000_000_000L },
        initialFrameId = initialFrameId,
    )

    private val allOn = FrameMetadataPublishOptions(timestamp = true, frameId = true)

    @Test
    fun nothingIsStampedBeforeArming() {
        val input = frame()
        val output = state().stamp(input)

        assertSame(input, output)
        assertNull(output.frameMetadata)
    }

    @Test
    fun nothingIsStampedAfterDisarming() {
        val state = state()
        state.arm(allOn)
        state.disarm()

        val input = frame()
        assertSame(input, state.stamp(input))
    }

    @Test
    fun defaultTimestampIsTheCaptureTimeOnTheWallClock() {
        val state = state()
        state.arm(FrameMetadataPublishOptions(timestamp = true, frameId = false))

        val metadata = state.stamp(frame()).frameMetadata!!

        assertEquals(1_699_999_999_000_000L, metadata.userTimestampUs)
        assertEquals(0L, metadata.frameId)
    }

    @Test
    fun onlyEnabledFieldsAreFilled() {
        val state = state()
        state.arm(FrameMetadataPublishOptions(timestamp = false, frameId = true))

        val metadata = state.stamp(frame()).frameMetadata!!

        assertEquals(0L, metadata.userTimestampUs)
        assertEquals(1L, metadata.frameId)
        assertNull(metadata.userData)
    }

    @Test
    fun frameIdsCountUpFromOne() {
        val state = state()
        state.arm(allOn)

        val ids = (1..3).map { state.stamp(frame()).frameMetadata!!.frameId }

        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun frameIdWrapsToOne() {
        val state = state(initialFrameId = FrameMetadataStampState.MAX_FRAME_ID)
        state.arm(allOn)

        val ids = (1..2).map { state.stamp(frame()).frameMetadata!!.frameId }

        assertEquals(listOf(FrameMetadataStampState.MAX_FRAME_ID, 1L), ids)
    }

    @Test
    fun metadataOnTheFrameWinsOverDefaults() {
        val state = state()
        state.arm(allOn)
        val userData = byteArrayOf(1, 2, 3)

        val metadata = state.stamp(frame(metadata = RtcFrameMetadata(123L, 0L, userData))).frameMetadata!!

        assertEquals(123L, metadata.userTimestampUs)
        // The frame ID was 0, so the default fills it.
        assertEquals(1L, metadata.frameId)
        assertArrayEquals(userData, metadata.userData)
    }

    @Test
    fun appValuesAreSentWithEveryOptionOff() {
        val state = state()
        state.arm(FrameMetadataPublishOptions(timestamp = false, frameId = false, userData = false))
        state.provider = FrameMetadataProvider { _, current -> current.copy(userData = byteArrayOf(9)) }

        val metadata = state.stamp(frame()).frameMetadata!!

        assertArrayEquals(byteArrayOf(9), metadata.userData)
    }

    @Test
    fun providerSeesTheDefaultsAndReplacesThem() {
        val state = state()
        state.arm(allOn)
        var seen: FrameMetadata? = null
        state.provider = FrameMetadataProvider { _, current ->
            seen = current
            FrameMetadata(userTimestampUs = 42L, frameId = 7L, userData = byteArrayOf(5))
        }

        val metadata = state.stamp(frame()).frameMetadata!!

        assertEquals(FrameMetadata(1_699_999_999_000_000L, 1L, null), seen)
        assertEquals(42L, metadata.userTimestampUs)
        assertEquals(7L, metadata.frameId)
        assertArrayEquals(byteArrayOf(5), metadata.userData)
    }

    @Test
    fun providerReturningNullKeepsTheValues() {
        val state = state()
        state.arm(allOn)
        state.provider = FrameMetadataProvider { _, _ -> null }

        val metadata = state.stamp(frame()).frameMetadata!!

        assertEquals(1_699_999_999_000_000L, metadata.userTimestampUs)
        assertEquals(1L, metadata.frameId)
    }

    @Test
    fun throwingProviderFallsBackToTheValuesItWasGiven() {
        val state = state()
        state.arm(allOn)
        state.provider = FrameMetadataProvider { _, _ -> throw IllegalStateException("app bug") }

        val metadata = state.stamp(frame()).frameMetadata!!

        assertEquals(1_699_999_999_000_000L, metadata.userTimestampUs)
        assertEquals(1L, metadata.frameId)
    }

    @Test
    fun providerZeroingEverythingRemovesMetadataFromTheFrame() {
        val state = state()
        state.arm(allOn)
        state.provider = FrameMetadataProvider { _, _ -> FrameMetadata() }

        val output = state.stamp(frame(metadata = RtcFrameMetadata(123L, 4L, null)))

        assertNull(output.frameMetadata)
    }

    @Test
    fun processorStampsAfterTheInnerProcessor() {
        // An inner processor that replaces each frame, as virtual background does.
        val inner = object : VideoProcessor {
            var output: VideoSink? = null
            override fun onCapturerStarted(success: Boolean) {}
            override fun onCapturerStopped() {}
            override fun setSink(sink: VideoSink?) {
                output = sink
            }

            override fun onFrameCaptured(frame: VideoFrame) {
                output?.onFrame(VideoFrame(frame.buffer, frame.rotation, frame.timestampNs))
            }
        }
        val processor = FrameMetadataStampingProcessor(inner)
        processor.state = state().apply { arm(allOn) }
        val received = mutableListOf<VideoFrame>()
        processor.setSink { received.add(it) }

        processor.onFrameCaptured(frame())

        assertEquals(1, received.size)
        assertEquals(1L, received.single().frameMetadata!!.frameId)
    }

    @Test
    fun processorForwardsTheTwoArgumentCallToTheInnerProcessor() {
        val inner = Mockito.mock(VideoProcessor::class.java)
        val processor = FrameMetadataStampingProcessor(inner)
        val input = frame()
        val parameters = VideoProcessor.FrameAdaptationParameters(0, 0, 1, 1, 1, 1, 0L, false)

        processor.onFrameCaptured(input, parameters)

        Mockito.verify(inner).onFrameCaptured(input, parameters)
    }

    @Test
    fun processorPassesFramesThroughWithoutAnInnerProcessor() {
        val processor = FrameMetadataStampingProcessor(null)
        val received = mutableListOf<VideoFrame>()
        processor.setSink { received.add(it) }
        val input = frame()

        processor.onFrameCaptured(input)

        assertSame(input, received.single())
    }

    @Test
    fun clearingTheSinkClearsTheInnerProcessorsSink() {
        val inner = Mockito.mock(VideoProcessor::class.java)
        val processor = FrameMetadataStampingProcessor(inner)

        processor.setSink { }
        processor.setSink(null)

        val inOrder = Mockito.inOrder(inner)
        inOrder.verify(inner).setSink(Mockito.notNull())
        inOrder.verify(inner).setSink(null)
    }
}
