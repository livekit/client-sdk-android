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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.I420Image
import android.media.ImageWriter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
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
import okhttp3.internal.wait

class NewSelfieBitmapVideoProcessor(eglBase: EglBase, dispatcher: CoroutineDispatcher) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)

    @RequiresApi(Build.VERSION_CODES.M)
    private val imageWriter = ImageWriter.newInstance(surface, 1)
    private var tempBitmap: Bitmap? = null
    private var maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var drawPaint = Paint()

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

    var counter = 0

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun processFrame(frame: VideoFrame) {
        var timer: Timer? = null
        counter++
        if (counter % 150 == 0) {
            timer = Timer()
        }

        // toI420 causes a retain, so a corresponding frameBuffer.release is needed when done.
        val frameBuffer = frame.buffer.toI420() ?: return
        val rotationDegrees = frame.rotation

        val image = I420Image(frame)
        timer?.tick("bitmap setup")
        // No longer need the original frame buffer any more.
        frameBuffer.release()
        frame.release()

        // Ready for segmentation processing.
        val inputImage = InputImage.fromMediaImage(image, rotationDegrees)
        val task = segmenter.process(inputImage)

        val latch = Mutex(true)
        task.addOnSuccessListener { segmentationMask ->


            timer?.tick("image processing")
            val maskBitmap = Bitmap.createBitmap(segmentationMask.width, segmentationMask.height, Bitmap.Config.ALPHA_8)
            // Do some image processing
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

            timer?.tick("bitmap drawing")

            // Prepare for creating the processed video frame.
            if (lastRotation != rotationDegrees) {
                surfaceTextureHelper?.setFrameRotation(rotationDegrees)
                lastRotation = rotationDegrees
            }

            if (lastWidth != frame.rotatedWidth || lastHeight != frame.rotatedHeight) {
                surfaceTextureHelper?.setTextureSize(frame.rotatedWidth, frame.rotatedHeight)
                lastWidth = frame.rotatedWidth
                lastHeight = frame.rotatedHeight
            }

            surfaceTextureHelper?.handler?.post {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    surface.lockHardwareCanvas()
                } else {
                    surface.lockCanvas(null)
                }

                if (canvas != null) {
                    // Create the video frame.
                    imageWriter.queueInputImage(image)
                    imageWriter.wait()
                    surface.unlockCanvasAndPost(canvas)

                    timer?.tick("canvas posted")
                    timer?.end("total")
                }
                image.close()
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


public class Timer {
    var startMs = SystemClock.elapsedRealtime()
    var start = SystemClock.elapsedRealtimeNanos()

    fun tick(message: String) {
        val end = SystemClock.elapsedRealtimeNanos()
        val time = end - start
        start = end
        Log.e("LOL", "$message took ${time / 1000000f}ms")
    }

    fun end(message: String) {
        val end = SystemClock.elapsedRealtime()
        val time = end - startMs

        Log.e("LOL", "$message took ${time}ms")
    }
}
