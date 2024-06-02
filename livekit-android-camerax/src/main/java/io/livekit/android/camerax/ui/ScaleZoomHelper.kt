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

@file:Suppress("unused")

package io.livekit.android.camerax.ui

import android.content.Context
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import com.google.common.util.concurrent.ListenableFuture
import io.livekit.android.camerax.ui.ScaleZoomHelper.Companion.createGestureDetector
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.util.LKLog
import kotlinx.coroutines.flow.StateFlow
import livekit.org.webrtc.getCameraX

/**
 * A helper class that handles zoom for a CameraX video capturer.
 *
 * For view based apps, [createGestureDetector] can be used to create a
 * GestureDetector that attaches to your views to provide pinch-to-zoom
 * functionality.
 *
 * For compose based apps, similar functionality can be implemented using
 * Modifier.pointerInput and detectTransformGestures:
 *
 * ```
 * val scaleZoomHelper = remember(videoTrack) {
 *     if (videoTrack is LocalVideoTrack) {
 *         ScaleZoomHelper(videoTrack)
 *     } else {
 *         null
 *     }
 * }
 *
 * VideoRenderer(
 *     modifier = modifier
 *         .pointerInput(scaleZoomHelper) {
 *             detectTransformGestures(
 *                 onGesture = { _, _, zoom, _ ->
 *                     scaleZoomHelper?.zoom(zoom)
 *                 },
 *             )
 *         },
 * )
 * ```
 */
class ScaleZoomHelper(
    private val cameraFlow: StateFlow<Camera?>?,
) {
    constructor(localVideoTrack: LocalVideoTrack) : this(localVideoTrack.capturer.getCameraX())

    init {
        if (cameraFlow != null) {
            LKLog.i { "null camera flow passed in to ScaleZoomHelper, zoom is disabled." }
        }
    }

    /**
     * Track the current target zoom, since cameraInfo.zoomState is on LiveData timers and can be out of date.
     */
    private var targetZoom: Float? = null

    /**
     * Track the current active zoom futures; only clear targetZoom when all futures are completed.
     */
    private var activeZoomFutures = mutableSetOf<ListenableFuture<Void>>()

    /**
     * Scales the current zoom value by [factor].
     *
     * This method handles clamping the resulting zoom value to within the camera's
     * minimum and maximum zoom. Best used with a scale gesture detector.
     *
     * @see CameraControl.setZoomRatio
     * @see createGestureDetector
     */
    @Synchronized
    fun zoom(factor: Float) {
        val camera = cameraFlow?.value ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return

        val currentZoom = targetZoom ?: zoomState.zoomRatio
        val newZoom = (currentZoom * factor).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

        if (newZoom != currentZoom) {
            targetZoom = newZoom
            val future = camera.cameraControl.setZoomRatio(newZoom)

            activeZoomFutures.add(future)
            future.addListener(
                {
                    synchronized(this) {
                        activeZoomFutures.remove(future)
                        if (activeZoomFutures.isEmpty()) {
                            targetZoom = null
                        }
                    }
                },
                { it.run() },
            )
        }
    }

    companion object {

        /**
         * Creates a ScaleGestureDetector that can be used with a view to provide pinch-to-zoom functionality.
         *
         * Example:
         * ```
         * val scaleGestureDetector = ScaleZoomHelper.createGestureDetector(viewBinding.renderer.context, localVideoTrack)
         * viewBinding.renderer.setOnTouchListener { _, event ->
         *     scaleGestureDetector?.onTouchEvent(event)
         *     return@setOnTouchListener true
         * }
         * ```
         */
        fun createGestureDetector(context: Context, localVideoTrack: LocalVideoTrack): ScaleGestureDetector {
            return createGestureDetector(context, localVideoTrack.capturer.getCameraX())
        }

        /**
         * Creates a ScaleGestureDetector that can be used with a view to provide pinch-to-zoom functionality.
         *
         * Example:
         * ```
         * val scaleGestureDetector = ScaleZoomHelper.createGestureDetector(viewBinding.renderer.context, localVideoTrack)
         * viewBinding.renderer.setOnTouchListener { _, event ->
         *     scaleGestureDetector?.onTouchEvent(event)
         *     return@setOnTouchListener true
         * }
         * ```
         */
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
