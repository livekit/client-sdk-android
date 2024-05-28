package io.livekit.android.camerax.ui

import android.content.Context
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import androidx.camera.core.Camera
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.util.LKLog
import kotlinx.coroutines.flow.StateFlow
import livekit.org.webrtc.getCameraX

class ScaleZoomHelper(
    private val cameraFlow: StateFlow<Camera?>?,
) {
    constructor(localVideoTrack: LocalVideoTrack) : this(localVideoTrack.capturer.getCameraX())

    init {
        if (cameraFlow != null) {
            LKLog.w { "null camera flow passed in to ScaleZoomHelper, zoom is disabled." }
        }
    }

    fun zoom(factor: Float) {
        val camera = cameraFlow?.value ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val currentZoom = zoomState.zoomRatio
        val newZoom = (currentZoom * factor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

        if (newZoom != currentZoom) {
            camera.cameraControl.setZoomRatio(newZoom)
        }
    }

    companion object {
        fun createGestureDetector(context: Context, localVideoTrack: LocalVideoTrack): ScaleGestureDetector {
            return createGestureDetector(context, localVideoTrack.capturer.getCameraX())
        }

        fun createGestureDetector(context: Context, cameraFlow: StateFlow<Camera?>?): ScaleGestureDetector {
            val helper = ScaleZoomHelper(cameraFlow)

            return ScaleGestureDetector(
                context,
                object : SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        helper.zoom(detector.scaleFactor)
                        return true
                    }
                },
            ).apply {
                isQuickScaleEnabled = false
            }
        }
    }
}
