package io.livekit.android.room

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator

object DeviceManager {

    enum class Kind {
        // Only camera input currently, audio input/output only has one option atm.
        CAMERA;
    }

    private val defaultDevices = mutableMapOf<Kind, String>()
    private val listeners =
        mutableMapOf<Kind, MutableList<OnDeviceAvailabilityChangeListener>>()

    private var hasSetupListeners = false

    @Synchronized
    internal fun setupListenersIfNeeded(context: Context) {
        if (hasSetupListeners) {
            return
        }

        hasSetupListeners = true
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                notifyListeners(Kind.CAMERA)
            }

            override fun onCameraUnavailable(cameraId: String) {
                notifyListeners(Kind.CAMERA)
            }

            override fun onCameraAccessPrioritiesChanged() {
                notifyListeners(Kind.CAMERA)
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun getDefaultDevice(kind: Kind): String? {
        return defaultDevices[kind]
    }

    fun setDefaultDevice(kind: Kind, deviceId: String?) {
        if (deviceId != null) {
            defaultDevices[kind] = deviceId
        } else {
            defaultDevices.remove(kind)
        }
    }

    /**
     * @return the list of device ids for [kind]
     */
    fun getDevices(context: Context, kind: Kind): List<String> {
        return when (kind) {
            Kind.CAMERA -> {
                val cameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
                    Camera2Enumerator(context)
                } else {
                    Camera1Enumerator()
                }
                cameraEnumerator.deviceNames.toList()
            }
        }
    }

    fun registerOnDeviceAvailabilityChange(
        kind: Kind,
        listener: OnDeviceAvailabilityChangeListener
    ) {
        if (listeners[kind] == null) {
            listeners[kind] = mutableListOf()
        }
        listeners[kind]!!.add(listener)
    }

    fun unregisterOnDeviceAvailabilityChange(
        kind: Kind,
        listener: OnDeviceAvailabilityChangeListener
    ) {
        listeners[kind]?.remove(listener)
    }

    private fun notifyListeners(kind: Kind) {
        listeners[kind]?.forEach {
            it.onDeviceAvailabilityChanged(kind)
        }
    }

    interface OnDeviceAvailabilityChangeListener {
        fun onDeviceAvailabilityChanged(kind: Kind)
    }
}