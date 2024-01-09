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

package io.livekit.android.videoencodedecode

import android.content.Context
import android.os.SystemClock
import androidx.annotation.ColorInt
import livekit.org.webrtc.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.TimeUnit

class DummyVideoCapturer(@ColorInt var color: Int) : VideoCapturer {
    var capturerObserver: CapturerObserver? = null
    val timer = Timer()
    var frameWidth = 0
    var frameHeight = 0
    private val tickTask: TimerTask = object : TimerTask() {
        override fun run() {
            tick()
        }
    }

    fun tick() {
        val videoFrame: VideoFrame = createFrame()
        this.capturerObserver?.onFrameCaptured(videoFrame)
        videoFrame.release()
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        applicationContext: Context,
        capturerObserver: CapturerObserver
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        frameWidth = width
        frameHeight = height
        this.timer.schedule(this.tickTask, 0L, (1000 / framerate).toLong())
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        this.timer.cancel()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}

    override fun dispose() {
        this.timer.cancel()
    }

    private fun createFrame(): VideoFrame {
        val captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())

        val buffer = JavaI420Buffer.allocate(this.frameWidth, this.frameHeight)
        encodeYUV420SP(buffer, this.color, frameWidth, frameHeight)

        this.color = (color + 1) % 0xFFFFFF
        return VideoFrame(buffer, 0, captureTimeNs)
    }

    override fun isScreencast(): Boolean {
        return false
    }

    companion object {

        // adapted from https://stackoverflow.com/a/13055615/295675
        fun encodeYUV420SP(yuvBuffer: JavaI420Buffer, color: Int, width: Int, height: Int) {
            var yIndex = 0
            var uvIndex = 0
            var R: Int
            var G: Int
            var B: Int
            var Y: Int
            var U: Int
            var V: Int

            val dataY: ByteBuffer = yuvBuffer.dataY
            val dataU = yuvBuffer.dataU
            val dataV = yuvBuffer.dataV

            var index = 0
            for (j in 0 until height) {
                for (i in 0 until width) {
                    R = color and 0xff0000 shr 16
                    G = color and 0xff00 shr 8
                    B = color and 0xff shr 0

                    // well known RGB to YUV algorithm
                    Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                    U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                    V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                    // NV21 has a plane of Y and interleaved planes of VU each sampled by a factor of 2
                    //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
                    //    pixel AND every other scanline.
                    dataY[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                    if (j % 2 == 0 && index % 2 == 0) {
                        dataV[uvIndex] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                        dataU[uvIndex] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                        uvIndex++
                    }
                    index++
                }
            }
        }
    }
}

private operator fun ByteBuffer.set(index: Int, value: Byte) {
    put(index, value)
}
