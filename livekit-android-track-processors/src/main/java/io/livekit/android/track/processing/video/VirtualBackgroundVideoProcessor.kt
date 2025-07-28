/*
 * Copyright 2024-2025 LiveKit, Inc.
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

package io.livekit.android.track.processing.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import io.livekit.android.room.track.video.NoDropVideoProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglRenderer
import livekit.org.webrtc.GlUtil
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.util.concurrent.Semaphore

/**
 * A virtual background video processor for the local camera video stream.
 *
 * By default, blurs the background of the video stream.
 * Setting [backgroundImage] will use the provided image instead.
 */
class VirtualBackgroundVideoProcessor(private val eglBase: EglBase, dispatcher: CoroutineDispatcher = Dispatchers.Default) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)
    private val backgroundTransformer = VirtualBackgroundTransformer()
    private val eglRenderer = EglRenderer(VirtualBackgroundVideoProcessor::class.java.simpleName)
        .apply {
            init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, backgroundTransformer)
            createEglSurface(surface)
        }

    private val scope = CoroutineScope(dispatcher)
    private val taskFlow = MutableSharedFlow<VideoFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Enables or disables the virtual background.
     *
     * Defaults to true.
     */
    var enabled: Boolean = true

    var backgroundImage: Bitmap? = null
        set(value) {
            field = value
            backgroundImageNeedsUpdating = true
        }
    private var backgroundImageNeedsUpdating = false

    init {
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build()
        segmenter = Segmentation.getClient(options)

        // Funnel processing into a single flow that won't buffer,
        // since processing may be slower than video capture.
        scope.launch {
            taskFlow.collect { frame ->
                processFrame(frame)
                frame.release()
            }
        }
    }

    private var lastMask: VirtualBackgroundTransformer.MaskHolder? = null

    private inner class ImageAnalyser : ImageAnalysis.Analyzer {
        val latch = Semaphore(1, true)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val image = imageProxy.image

            if (enabled && image != null) {
                // Put 0 for rotation degrees
                // We'll rotate it together with the original video frame in the shader.
                val inputImage = InputImage.fromMediaImage(image, 0)
                latch.acquire()
                val task = segmenter.process(inputImage)
                task.addOnSuccessListener { mask ->
                    val holder = VirtualBackgroundTransformer.MaskHolder(mask.width, mask.height, mask.buffer)
                    lastMask = holder
                    latch.release()
                }
                latch.acquire()
                latch.release()
            }

            imageProxy.close()
        }
    }

    @Suppress("unused")
    val imageAnalyzer: ImageAnalysis.Analyzer = ImageAnalyser()

    override fun onCapturerStarted(started: Boolean) {
        if (started) {
            surfaceTextureHelper.stopListening()
            surfaceTextureHelper.startListening { frame ->
                targetSink?.onFrame(frame)
            }
        }
    }

    override fun onCapturerStopped() {
        surfaceTextureHelper.stopListening()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        // If disabled, just pass through to the sink.
        if (!enabled) {
            targetSink?.onFrame(frame)
            return
        }

        try {
            frame.retain()
        } catch (e: Exception) {
            return
        }

        // If the frame is succesfully emitted, the process flow will own the frame.
        if (!taskFlow.tryEmit(frame)) {
            frame.release()
        }
    }

    fun processFrame(frame: VideoFrame) {
        if (lastRotation != frame.rotation) {
            lastRotation = frame.rotation
            backgroundImageNeedsUpdating = true
        }

        if (lastWidth != frame.rotatedWidth || lastHeight != frame.rotatedHeight) {
            surfaceTextureHelper.setTextureSize(frame.rotatedWidth, frame.rotatedHeight)
            lastWidth = frame.rotatedWidth
            lastHeight = frame.rotatedHeight
            backgroundImageNeedsUpdating = true
        }

        frame.retain()
        surfaceTextureHelper.handler.post {
            val backgroundImage = this.backgroundImage
            if (backgroundImageNeedsUpdating && backgroundImage != null) {
                val imageAspect = backgroundImage.width / backgroundImage.height.toFloat()
                val targetAspect = frame.rotatedWidth / frame.rotatedHeight.toFloat()
                var sx = 0
                var sy = 0
                var sWidth = backgroundImage.width
                var sHeight = backgroundImage.height

                if (imageAspect > targetAspect) {
                    sWidth = Math.round(backgroundImage.height * targetAspect)
                    sx = Math.round((backgroundImage.width - sWidth) / 2f)
                } else {
                    sHeight = Math.round(backgroundImage.width / targetAspect)
                    sy = Math.round((backgroundImage.height - sHeight) / 2f)
                }

                val diffAspect = targetAspect / imageAspect

                val matrix = Matrix()

                matrix.postRotate(-frame.rotation.toFloat())

                val resizedImage = Bitmap.createBitmap(
                    backgroundImage,
                    sx,
                    sy,
                    sWidth,
                    sHeight,
                    matrix,
                    true,
                )
                backgroundTransformer.backgroundImage = resizedImage
                backgroundImageNeedsUpdating = false
            }

            lastMask?.let {
                backgroundTransformer.updateMask(it)
            }
            lastMask = null
            eglRenderer.onFrame(frame)
            frame.release()
        }
    }

    override fun setSink(sink: VideoSink?) {
        targetSink = sink
    }

    fun dispose() {
        scope.cancel()
        segmenter.close()
        surfaceTextureHelper.stopListening()
        surfaceTextureHelper.dispose()
        surface.release()
        eglRenderer.release()
        backgroundTransformer.release()
        GlUtil.checkNoGLES2Error("VirtualBackgroundVideoProcessor.dispose")
    }
}
