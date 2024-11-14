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

package livekit.org.webrtc

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Build.VERSION
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.UseCase
import androidx.lifecycle.LifecycleOwner

/**
 * @suppress
 */
@ExperimentalCamera2Interop
class CameraXEnumerator(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val useCases: Array<out UseCase> = emptyArray(),
) : Camera2Enumerator(context) {

    override fun createCapturer(
        deviceName: String?,
        eventsHandler: CameraVideoCapturer.CameraEventsHandler?,
    ): CameraVideoCapturer {
        return CameraXCapturer(context, lifecycleOwner, deviceName, eventsHandler, useCases)
    }

    companion object {
        fun getSupportedSizes(camera: Camera2CameraInfo): List<Size> {
            val streamMap = camera.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportLevel = camera.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val nativeSizes = streamMap!!.getOutputSizes(SurfaceTexture::class.java)
            val sizes = convertSizes(nativeSizes)
            val activeArraySize: Rect? = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            return if (VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 &&
                supportLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY &&
                activeArraySize != null
            ) {
                val filteredSizes = ArrayList<Size>()
                for (size in sizes) {
                    if (activeArraySize.width() * size.height == activeArraySize.height() * size.width) {
                        filteredSizes.add(size)
                    }
                }
                filteredSizes
            } else {
                sizes
            }
        }

        private fun convertSizes(cameraSizes: Array<android.util.Size>): List<Size> {
            val sizes: MutableList<Size> = ArrayList()
            for (size in cameraSizes) {
                sizes.add(Size(size.width, size.height))
            }
            return sizes
        }
    }
}
