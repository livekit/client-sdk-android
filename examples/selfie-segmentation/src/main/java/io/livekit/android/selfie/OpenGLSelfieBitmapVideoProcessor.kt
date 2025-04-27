package io.livekit.android.selfie

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.graphics.scaleMatrix
import androidx.core.graphics.withMatrix
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

class OpenGLSelfieBitmapVideoProcessor(eglBase: EglBase, dispatcher: CoroutineDispatcher) : NoDropVideoProcessor() {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)
    private var tempBitmap: Bitmap? = null
    private var maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
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

        val dataY = frameBuffer.dataY
        val dataU = frameBuffer.dataU
        val dataV = frameBuffer.dataV
        val nv12Buffer = ByteBuffer.allocateDirect(dataY.limit() / 4 + dataU.limit() / 4 + dataV.limit() / 4)

        // YuvImage only supports NV21, but NV21 is just NV12 with the U and V planes swapped.
        YuvHelper.I420ToNV12(
            frameBuffer.dataY,
            frameBuffer.strideY,
            frameBuffer.dataV,
            frameBuffer.strideV,
            frameBuffer.dataU,
            frameBuffer.strideU,
            nv12Buffer,
            frameBuffer.width / 2,
            frameBuffer.height / 2,
        )

        // Use YuvImage to convert to bitmap
        val yuvImage =
            YuvImage(nv12Buffer.array(), ImageFormat.NV21, frameBuffer.width / 2, frameBuffer.height / 2, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, frameBuffer.height), 100, stream)

        timer?.tick("yuvImage compress")

        val original = BitmapFactory.decodeByteArray(
            stream.toByteArray(),
            0,
            stream.size(),
            BitmapFactory.Options().apply { inMutable = true },
        )
        var resultBitmap = tempBitmap
        if (resultBitmap == null || resultBitmap.width != original.width || resultBitmap.height != original.height) {
            tempBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            resultBitmap = tempBitmap!!
        } else {
            resultBitmap.eraseColor(0)
        }


        timer?.tick("bitmap setup")
        // No longer need the original frame buffer any more.
        frameBuffer.release()
        frame.release()

        // Ready for segmentation processing.
        val inputImage = InputImage.fromBitmap(original, 0)
        val task = segmenter.process(inputImage)

        val latch = Mutex(true)
        task.addOnSuccessListener { segmentationMask ->


            timer?.tick("segmentation analysis")
            val mask = segmentationMask.buffer
            mask.rewind()
            val maskPixelBuffer = ByteBuffer.allocate(segmentationMask.width * segmentationMask.height)

            // Do some image processing
            for (i in 0 until segmentationMask.height) {
                for (j in 0 until segmentationMask.width) {
                    val backgroundConfidence = 1 - mask.float

                    if (backgroundConfidence > 0.8f) {
                        maskPixelBuffer.put(0xFF.toByte())
                    } else {
                        maskPixelBuffer.put(0x00.toByte())
                    }
                }
            }

            timer?.tick("image processing")
            maskPixelBuffer.rewind()
            val maskBitmap = Bitmap.createBitmap(segmentationMask.width, segmentationMask.height, Bitmap.Config.ALPHA_8)
            maskBitmap.copyPixelsFromBuffer(maskPixelBuffer)
            // Do some image processing
            val resultCanvas = Canvas(resultBitmap)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            resultCanvas.drawBitmap(original, 0f, 0f, null)
            resultCanvas.withMatrix(scaleMatrix(2f, 2f)) {
                drawBitmap(maskBitmap, 0f, 0f, Paint())
            }

            timer?.tick("bitmap drawing")

            // Prepare for creating the processed video frame.
            if (lastRotation != rotationDegrees) {
                surfaceTextureHelper?.setFrameRotation(rotationDegrees)
                lastRotation = rotationDegrees
            }

            if (lastWidth != original.width || lastHeight != original.height) {
                surfaceTextureHelper?.setTextureSize(original.width, original.height)
                lastWidth = original.width
                lastHeight = original.height
            }

            surfaceTextureHelper?.handler?.post {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    surface.lockHardwareCanvas()
                } else {
                    surface.lockCanvas(null)
                }

                if (canvas != null) {
                    // Create the video frame.
                    canvas.drawBitmap(resultBitmap, Matrix(), drawPaint)
                    surface.unlockCanvasAndPost(canvas)

                    timer?.tick("canvas posted")
                    timer?.end("total")
                }
                original.recycle()
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

