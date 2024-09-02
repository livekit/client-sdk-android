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

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.core.graphics.set
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import io.livekit.android.room.track.video.NoDropVideoProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SelfieBitmapVideoProcessor(eglBase: EglBase, dispatcher: CoroutineDispatcher) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)

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
        if (started) {
            surfaceTextureHelper.startListening { frame ->
                targetSink?.onFrame(frame)
            }
        }
    }

    override fun onCapturerStopped() {
        surfaceTextureHelper.stopListening()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        if (taskFlow.tryEmit(frame)) {
            frame.retain()
        }
    }

    suspend fun processFrame(frame: VideoFrame) {
        // toI420 causes a retain, so a corresponding frameBuffer.release is needed when done.
        val frameBuffer = frame.buffer.toI420() ?: return
        val rotationDegrees = frame.rotation

        val dataY = frameBuffer.dataY
        val dataU = frameBuffer.dataU
        val dataV = frameBuffer.dataV
        val nv12Buffer = ByteBuffer.allocateDirect(dataY.limit() + dataU.limit() + dataV.limit())

        // For some reason, I420ToNV12 actually expects YV12
        YuvHelper.I420ToNV12(
            frameBuffer.dataY,
            frameBuffer.strideY,
            frameBuffer.dataV,
            frameBuffer.strideV,
            frameBuffer.dataU,
            frameBuffer.strideU,
            nv12Buffer,
            frameBuffer.width,
            frameBuffer.height,
        )

        // Use YuvImage to convert to bitmap
        val yuvImage = YuvImage(nv12Buffer.array(), ImageFormat.NV21, frameBuffer.width, frameBuffer.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, frameBuffer.width, frameBuffer.height), 100, stream)

        val bitmap = BitmapFactory.decodeByteArray(
            stream.toByteArray(),
            0,
            stream.size(),
            BitmapFactory.Options().apply { inMutable = true },
        )

        // No longer need the original frame buffer any more.
        frameBuffer.release()
        frame.release()

        // Ready for segementation processing.
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val task = segmenter.process(inputImage)

        val latch = Mutex(true)
        task.addOnSuccessListener { segmentationMask ->
            val mask = segmentationMask.buffer

            // Do some image processing
            for (y in 0 until segmentationMask.height) {
                for (x in 0 until segmentationMask.width) {
                    val backgroundConfidence = 1 - mask.float

                    if (backgroundConfidence > 0.8f) {
                        bitmap[x, y] = Color.GREEN // Color off the background
                    }
                }
            }

            // Prepare for creating the processed video frame.
            if (lastRotation != rotationDegrees) {
                surfaceTextureHelper?.setFrameRotation(rotationDegrees)
                lastRotation = rotationDegrees
            }

            if (lastWidth != bitmap.width || lastHeight != bitmap.height) {
                surfaceTextureHelper?.setTextureSize(bitmap.width, bitmap.height)
                lastWidth = bitmap.width
                lastHeight = bitmap.height
            }

            surfaceTextureHelper?.handler?.post {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    surface.lockHardwareCanvas()
                } else {
                    surface.lockCanvas(null)
                }

                if (canvas != null) {
                    // Create the video frame.
                    canvas.drawBitmap(bitmap, Matrix(), Paint())
                    surface.unlockCanvasAndPost(canvas)
                }
                bitmap.recycle()
                latch.unlock()
            }
        }.addOnFailureListener {
            Log.e("SelfieVideoProcessor", "failed to process frame!")
        }
        latch.lock()
    }

    override fun setSink(sink: VideoSink?) {
        targetSink = sink
    }

    fun dispose() {
        segmenter.close()
        surfaceTextureHelper.stopListening()
        surfaceTextureHelper.dispose()
        scope.cancel()
    }
}
