package io.livekit.android.selfie

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import java.nio.ByteBuffer

class SelfieVideoProcessor : VideoProcessor {

    private var targetSink: VideoSink? = null
    private val segmenter: Segmenter

    init {
        val options =
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build()
        segmenter = Segmentation.getClient(options)
    }

    override fun onCapturerStarted(started: Boolean) {
    }

    override fun onCapturerStopped() {
    }

    override fun onFrameCaptured(frame: VideoFrame) {

        frame.retain()
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
            for (i in 0 until segmentationMask.height) {
                for (j in 0 until segmentationMask.width) {
                    val backgroundConfidence = 1 - mask.float

                    if (backgroundConfidence > 0.5f) {
                        val position = dataY.position()
                        val yValue = 0x80.toByte()
                        dataY.position(position)
                        dataY.put(yValue)
                    } else {
                        dataY.position(dataY.position() + 1)
                    }
                }
            }
            targetSink?.onFrame(VideoFrame(frameBuffer, frame.rotation, frame.timestampNs))
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
