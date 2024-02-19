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

import android.graphics.Bitmap
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
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SelfieBitmapVideoProcessor(eglBase: EglBase, dispatcher: CoroutineDispatcher) : VideoProcessor {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

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
        val frameBuffer = frame.buffer.toI420() ?: return

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

        val yuvImage = YuvImage(nv12Buffer.array(), ImageFormat.NV21, frameBuffer.width, frameBuffer.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, frameBuffer.width, frameBuffer.height), 100, stream)

        val decodedBmp = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size(), BitmapFactory.Options())
        val rotatedBmp = rotateBitmap(decodedBmp, frame.rotation)

        // No longer need the original frame buffer any more.
        frameBuffer.release()
        frame.release()

        val inputImage = InputImage.fromBitmap(rotatedBmp, 0)
        val task = segmenter.process(inputImage)

        val latch = Mutex(true)
        task.addOnSuccessListener { segmentationMask ->
            val mask = segmentationMask.buffer

            for (y in 0 until segmentationMask.height) {
                for (x in 0 until segmentationMask.width) {
                    val backgroundConfidence = 1 - mask.float

                    if (backgroundConfidence > 0.8f) {
                        rotatedBmp[x, y] = Color.GREEN // Color off the background
                    }
                }
            }

            if (lastWidth != rotatedBmp.width || lastHeight != rotatedBmp.height) {
                surfaceTextureHelper?.setTextureSize(rotatedBmp.width, rotatedBmp.height)
                lastWidth = rotatedBmp.width
                lastHeight = rotatedBmp.height
            }

            surfaceTextureHelper?.handler?.post {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    surface.lockHardwareCanvas()
                } else {
                    surface.lockCanvas(null)
                }

                if (canvas != null) {
                    canvas.drawBitmap(rotatedBmp, Matrix(), Paint())
                    surface.unlockCanvasAndPost(canvas)
                }
                rotatedBmp.recycle()
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

    /** Rotates a bitmap if it is converted from a bytebuffer.  */
    private fun rotateBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ): Bitmap {
        val matrix = Matrix()

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees.toFloat())

        // Mirror the image along the X or Y axis.
        matrix.postScale(if (flipX) -1.0f else 1.0f, if (flipY) -1.0f else 1.0f)
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        return rotatedBitmap
    }
}
