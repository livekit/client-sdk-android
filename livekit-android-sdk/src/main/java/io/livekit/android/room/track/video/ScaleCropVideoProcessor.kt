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

package io.livekit.android.room.track.video

import livekit.org.webrtc.VideoFrame
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A video processor that scales down and crops to match
 * the target dimensions and aspect ratio.
 *
 * If the frames are smaller than the target dimensions,
 * upscaling will not occur, instead only cropping to match
 * the aspect ratio.
 */
class ScaleCropVideoProcessor(
    var targetWidth: Int,
    var targetHeight: Int,
) : ChainVideoProcessor() {

    override fun onFrameCaptured(frame: VideoFrame) {
        if (frame.rotatedWidth == targetWidth && frame.rotatedHeight == targetHeight) {
            // already the perfect size, just pass along the frame.
            continueChain(frame)
            return
        }

        val width = frame.buffer.width
        val height = frame.buffer.height
        // Ensure target dimensions don't exceed source dimensions
        val scaleWidth: Int
        val scaleHeight: Int

        if (targetWidth > width || targetHeight > height) {
            // Calculate scale factor to fit within source dimensions
            val widthScale = targetWidth.toDouble() / width
            val heightScale = targetHeight.toDouble() / height
            val scale = max(widthScale, heightScale)

            // Apply scale to target dimensions
            scaleWidth = (targetWidth / scale).roundToInt()
            scaleHeight = (targetHeight / scale).roundToInt()
        } else {
            scaleWidth = targetWidth
            scaleHeight = targetHeight
        }

        val sourceRatio = width.toDouble() / height
        val targetRatio = scaleWidth.toDouble() / scaleHeight

        val cropWidth: Int
        val cropHeight: Int

        // Calculate crop dimension
        if (sourceRatio > targetRatio) {
            // source is wider, crop height
            cropHeight = height
            cropWidth = (height * targetRatio).roundToInt()
        } else {
            // source is taller, crop width
            cropWidth = width
            cropHeight = (width / targetRatio).roundToInt()
        }

        // Calculate center offsets
        val offsetX = (width - cropWidth) / 2
        val offsetY = (height - cropHeight) / 2
        val newBuffer = frame.buffer.cropAndScale(
            offsetX,
            offsetY,
            cropWidth,
            cropHeight,
            scaleWidth,
            scaleHeight,
        )

        val croppedFrame = VideoFrame(newBuffer, frame.rotation, frame.timestampNs)
        continueChain(croppedFrame)
        croppedFrame.release()
    }
}
