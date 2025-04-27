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

import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import io.livekit.android.room.track.video.NoDropVideoProcessor
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlRectDrawer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.Semaphore

class ShaderBitmapVideoProcessor(eglBase: EglBase, dispatcher: CoroutineDispatcher) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)
    private val eglRenderer = EglRenderer(ShaderBitmapVideoProcessor::class.java.simpleName)
        .apply {
            init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
            createEglSurface(surface)
        }

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

    private var lastMask: SegmentationMask? = null

    private inner class ImageAnalyser : ImageAnalysis.Analyzer {
        val latch = Semaphore(1, true)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            LKLog.e { "image received: ${imageProxy.width}x${imageProxy.height}" }
            val image = imageProxy.image

            if (image != null) {
                val inputImage = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)

                latch.acquire()
                val task = segmenter.process(inputImage)
                task.addOnSuccessListener { mask ->
                    LKLog.e { "analyzed image with mask: ${mask.width}x${mask.height}" }
                    lastMask = mask
                    latch.release()
                }
                latch.acquire()
                latch.release()
            }

            imageProxy.close()
        }

    }

    val imageAnalyzer: ImageAnalysis.Analyzer = ImageAnalyser()

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
        // Ready for segementation processing.

        LKLog.e { "process frame" }
        targetSink?.onFrame(frame)
//        val latch = Mutex(true)
//        surfaceTextureHelper?.handler?.post {
////                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
////                    surface.lockHardwareCanvas()
////                } else {
////                    surface.lockCanvas(null)
////                }
////
////                if (canvas != null) {
////                    eglRenderer.onFrame(frame)
////                    surface.unlockCanvasAndPost(canvas)
////                }
//
//            eglRenderer.onFrame(frame)
//            latch.unlock()
//        }
//        latch.lock()

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
