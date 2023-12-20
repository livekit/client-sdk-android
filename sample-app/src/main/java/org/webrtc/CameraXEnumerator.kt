package org.webrtc

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Build.VERSION
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.LifecycleOwner


@ExperimentalCamera2Interop
class CameraXEnumerator(
    context: Context,
    private val lifecycleOwner: LifecycleOwner
) : Camera2Enumerator(context) {

    override fun createCapturer(deviceName: String?, eventsHandler: CameraVideoCapturer.CameraEventsHandler?): CameraVideoCapturer {
        return CameraXCapturer(context, lifecycleOwner, deviceName, eventsHandler)
    }

    companion object {
        fun getSupportedSizes(camera: Camera2CameraInfo): List<Size> {
            val streamMap = camera.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val supportLevel = camera.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val nativeSizes = streamMap!!.getOutputSizes(SurfaceTexture::class.java)
            val sizes = convertSizes(nativeSizes)!!
            val activeArraySize: Rect? = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            return if (VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1 && supportLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY && activeArraySize != null) {
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

        private fun convertSizes(cameraSizes: Array<android.util.Size>): List<Size>? {
            val sizes: MutableList<Size> = ArrayList()
            for (size in cameraSizes) {
                sizes.add(Size(size.width, size.height))
            }
            return sizes
        }
    }
}
