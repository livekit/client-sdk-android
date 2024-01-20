/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package io.livekit.android.room.track.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.Surface
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer

/**
 * A [VideoCapturer] that can be manually driven by passing in [Bitmap].
 *
 * Once [startCapture] is called, call [pushBitmap] to render images as video frames.
 */
open class BitmapFrameCapturer : VideoCapturer {
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var disposed = false

    private var rotation = 0
    private var width = 0
    private var height = 0

    private val stateLock = Any()

    private var surface: Surface? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        observer: CapturerObserver,
    ) {
        synchronized(stateLock) {
            this.surfaceTextureHelper = surfaceTextureHelper
            this.capturerObserver = observer
            surface = Surface(surfaceTextureHelper.surfaceTexture)
        }
    }

    private fun checkNotDisposed() {
        check(!disposed) { "Capturer is disposed." }
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        synchronized(stateLock) {
            checkNotDisposed()
            checkNotNull(surfaceTextureHelper) { "BitmapFrameCapturer must be initialized before calling startCapture." }
            capturerObserver?.onCapturerStarted(true)
            surfaceTextureHelper?.startListening { frame -> capturerObserver?.onFrameCaptured(frame) }
        }
    }

    override fun stopCapture() {
        synchronized(stateLock) {
            surfaceTextureHelper?.stopListening()
            capturerObserver?.onCapturerStopped()
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Do nothing.
        // These attributes are driven by the bitmaps fed in.
    }

    override fun dispose() {
        synchronized(stateLock) {
            if (disposed) {
                return
            }

            stopCapture()
            surface?.release()
            disposed = true
        }
    }

    override fun isScreencast(): Boolean = false

    fun pushBitmap(bitmap: Bitmap, rotationDegrees: Int) {
        synchronized(stateLock) {
            if (disposed) {
                return
            }

            checkNotNull(surfaceTextureHelper)
            checkNotNull(surface)
            if (this.rotation != rotationDegrees) {
                surfaceTextureHelper?.setFrameRotation(rotationDegrees)
                this.rotation = rotationDegrees
            }

            if (this.width != bitmap.width || this.height != bitmap.height) {
                surfaceTextureHelper?.setTextureSize(bitmap.width, bitmap.height)
                this.width = bitmap.width
                this.height = bitmap.height
            }

            surfaceTextureHelper?.handler?.post {
                val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    surface?.lockHardwareCanvas()
                } else {
                    surface?.lockCanvas(null)
                }

                if (canvas != null) {
                    canvas.drawBitmap(bitmap, Matrix(), Paint())
                    surface?.unlockCanvasAndPost(canvas)
                }
            }
        }
    }
}
