package android.media

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer

class I420Image(val videoFrame: VideoFrame) : Image() {

    val i420Buffer = videoFrame.buffer.toI420()!!
    override fun close() {
        i420Buffer.release()

    }

    fun throwISEIfImageIsInvalid() {

    }

    fun isAttachable(): Boolean {
        return true
    }

    override fun getHardwareBuffer(): HardwareBuffer? {
        return super.getHardwareBuffer()
    }

    override fun getFormat(): Int = ImageFormat.YUV_420_888

    override fun getWidth(): Int = i420Buffer.width

    override fun getHeight(): Int = i420Buffer.height

    override fun getTimestamp(): Long = videoFrame.timestampNs

    override fun getPlanes(): Array<Plane> {
        return Array(3) { index ->
            when (index) {
                0 -> {
                    I420Plane(
                        i420Buffer.strideY,
                        i420Buffer.strideY,
                        i420Buffer.dataY,
                    )
                }

                1 -> {
                    I420Plane(
                        i420Buffer.strideU,
                        i420Buffer.strideU,
                        i420Buffer.dataU,
                    )
                }

                2 -> {
                    I420Plane(
                        i420Buffer.strideV,
                        i420Buffer.strideV,
                        i420Buffer.dataV,
                    )
                }

                else -> {
                    throw IndexOutOfBoundsException()
                }
            }
        }
    }

    data class I420Plane(
        private val rowStride: Int,
        private val pixelStride: Int,
        private val buffer: ByteBuffer,
    ) : Image.Plane() {
        override fun getRowStride(): Int = rowStride

        override fun getPixelStride(): Int = pixelStride

        override fun getBuffer(): ByteBuffer = buffer
    }
}
