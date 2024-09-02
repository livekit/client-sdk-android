/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.selfie

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import io.livekit.android.room.track.video.NoDropVideoProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.nio.ByteBuffer

class SelfieVideoProcessor(dispatcher: CoroutineDispatcher) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private val scope = CoroutineScope(dispatcher)
    private val taskFlow = MutableSharedFlow<VideoFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    init {
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build()
        segmenter = Segmentation.getClient(options)

        // Funnel processing into a single flow that won't buffer,
        // since processing will be slower than video capture
        scope.launch {
            taskFlow.collect { frame ->
                processFrame(frame)
            }
        }
    }

    override fun onCapturerStarted(started: Boolean) {
    }

    override fun onCapturerStopped() {
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        if (taskFlow.tryEmit(frame)) {
            frame.retain()
        }
    }

    fun processFrame(frame: VideoFrame) {
        // toI420 causes a retain, so a corresponding frameBuffer.release is needed when done.
        val frameBuffer = frame.buffer.toI420() ?: return
        val byteBuffer = ByteBuffer.allocateDirect(frameBuffer.dataY.limit() + frameBuffer.dataV.limit() + frameBuffer.dataU.limit())
            // YV12 is exactly like I420, but the order of the U and V planes is reversed.
            // In the name, "YV" refers to the plane order: Y, then V (then U).
            .put(frameBuffer.dataY)
            .put(frameBuffer.dataV)
            .put(frameBuffer.dataU)

        val image = InputImage.fromByteBuffer(
            byteBuffer,
            frameBuffer.width,
            frameBuffer.height,
            0,
            InputImage.IMAGE_FORMAT_YV12,
        )

        val task = segmenter.process(image)
        task.addOnSuccessListener { segmentationMask ->
            val mask = segmentationMask.buffer

            val dataY = frameBuffer.dataY

            // Do some image processing
            for (i in 0 until segmentationMask.height) {
                for (j in 0 until segmentationMask.width) {
                    val backgroundConfidence = 1 - mask.float

                    if (backgroundConfidence > 0.8f) {
                        val position = dataY.position()
                        val yValue = 0x80.toByte()
                        dataY.position(position)
                        dataY.put(yValue)
                    } else {
                        dataY.position(dataY.position() + 1)
                    }
                }
            }

            // Send the final frame off to the sink.
            targetSink?.onFrame(VideoFrame(frameBuffer, frame.rotation, frame.timestampNs))

            // Release any remaining resources
            frameBuffer.release()
            frame.release()
        }.addOnFailureListener {
            Log.e("SelfieVideoProcessor", "failed to process frame!")
        }
    }

    override fun setSink(sink: VideoSink?) {
        targetSink = sink
    }

    fun dispose() {
        segmenter.close()
    }
}
