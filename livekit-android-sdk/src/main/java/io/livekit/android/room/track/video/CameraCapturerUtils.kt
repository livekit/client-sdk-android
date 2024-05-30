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

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package io.livekit.android.room.track.video

import android.content.Context
import android.hardware.camera2.CameraManager
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.util.LKLog
import livekit.org.webrtc.Camera1Capturer
import livekit.org.webrtc.Camera1Enumerator
import livekit.org.webrtc.Camera1Helper
import livekit.org.webrtc.Camera2Capturer
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraEnumerator
import livekit.org.webrtc.VideoCapturer

/**
 * Various utils for handling camera capturers.
 */
object CameraCapturerUtils {

    private val cameraProviders = mutableListOf<CameraProvider>().apply {
        add(createCamera1Provider())
        add(createCamera2Provider())
    }

    /**
     * Register external camera provider
     */
    fun registerCameraProvider(cameraProvider: CameraProvider) {
        LKLog.d { "Registering camera provider: Camera version:${cameraProvider.cameraVersion}" }
        cameraProviders.add(cameraProvider)
    }

    /**
     * Unregister external camera provider
     */
    fun unregisterCameraProvider(cameraProvider: CameraProvider) {
        LKLog.d { "Removing camera provider: Camera version:${cameraProvider.cameraVersion}" }
        cameraProviders.remove(cameraProvider)
    }

    /**
     * Obtain a CameraEnumerator based on platform capabilities.
     */
    fun createCameraEnumerator(context: Context): CameraEnumerator {
        return getCameraProvider(context).provideEnumerator(context)
    }

    /**
     * Create a CameraProvider based on platform capabilities.
     *
     * Picks CameraProvider of highest available version that is supported on device
     */
    private fun getCameraProvider(context: Context): CameraProvider {
        return cameraProviders
            .sortedByDescending { it.cameraVersion }
            .first {
                it.isSupported(context)
            }
    }

    /**
     * Creates a Camera capturer.
     */
    fun createCameraCapturer(
        context: Context,
        options: LocalVideoTrackOptions,
    ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
        val pair = createCameraCapturer(context, getCameraProvider(context), options)

        if (pair == null) {
            LKLog.d { "Failed to open camera" }
            return null
        }
        return pair
    }

    private fun createCameraCapturer(
        context: Context,
        provider: CameraProvider,
        options: LocalVideoTrackOptions,
    ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
        val cameraEventsDispatchHandler = CameraEventsDispatchHandler()
        val cameraEnumerator = provider.provideEnumerator(context)
        val targetDeviceName = cameraEnumerator.findCamera(options.deviceId, options.position) ?: return null
        val targetVideoCapturer = provider.provideCapturer(context, options, cameraEventsDispatchHandler)

        // back fill any missing information
        val newOptions = options.copy(
            deviceId = targetDeviceName,
            position = cameraEnumerator.getCameraPosition(targetDeviceName),
        )

        if (targetVideoCapturer !is VideoCapturerWithSize) {
            LKLog.w { "unknown CameraCapturer class: ${targetVideoCapturer.javaClass.canonicalName}. Reported dimensions may be inaccurate." }
        }
        return Pair(
            targetVideoCapturer,
            newOptions,
        )
    }

    private fun createCamera1Provider() = object : CameraProvider {
        private val enumerator by lazy { Camera1Enumerator(true) }

        override val cameraVersion = 1

        override fun provideEnumerator(context: Context) = enumerator

        override fun provideCapturer(
            context: Context,
            options: LocalVideoTrackOptions,
            eventsHandler: CameraEventsDispatchHandler,
        ): VideoCapturer {
            val targetDeviceName = enumerator.findCamera(options.deviceId, options.position)
            // Cache supported capture formats ahead of time to avoid future camera locks.
            Camera1Helper.getSupportedFormats(Camera1Helper.getCameraId(targetDeviceName))
            val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, eventsHandler)
            return Camera1CapturerWithSize(
                targetVideoCapturer as Camera1Capturer,
                targetDeviceName,
                eventsHandler,
            )
        }

        override fun isSupported(context: Context) = true
    }

    private fun createCamera2Provider() = object : CameraProvider {
        private var enumerator: Camera2Enumerator? = null

        override val cameraVersion = 2

        override fun provideEnumerator(context: Context): CameraEnumerator =
            enumerator ?: Camera2Enumerator(context).also {
                enumerator = it
            }

        override fun provideCapturer(
            context: Context,
            options: LocalVideoTrackOptions,
            eventsHandler: CameraEventsDispatchHandler,
        ): VideoCapturer {
            val enumerator = provideEnumerator(context)
            val targetDeviceName = enumerator.findCamera(options.deviceId, options.position)
            val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, eventsHandler)
            return Camera2CapturerWithSize(
                targetVideoCapturer as Camera2Capturer,
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                targetDeviceName,
                eventsHandler,
            )
        }

        override fun isSupported(context: Context) = Camera2Enumerator.isSupported(context)
    }

    /**
     * Finds the device id of first available camera based on the criteria given. Returns null if no camera matches the criteria.
     *
     * @param deviceId an id of a camera. Available device ids can be found through [CameraEnumerator.getDeviceNames]. If null, device id search is skipped. Defaults to null.
     * @param position the position of a camera. If null, search based on camera position is skipped. Defaults to null.
     * @param fallback if true, when no camera is found by device id/position search, the first available camera on the list will be returned.
     */
    fun CameraEnumerator.findCamera(
        deviceId: String? = null,
        position: CameraPosition? = null,
        fallback: Boolean = true,
    ): String? {
        var targetDeviceName: String? = null
        // Prioritize search by deviceId first
        if (deviceId != null) {
            targetDeviceName = findCamera { deviceName -> deviceName == deviceId }
        }

        // Search by camera position
        if (targetDeviceName == null && position != null) {
            targetDeviceName = findCamera { deviceName ->
                getCameraPosition(deviceName) == position
            }
        }

        // Fall back by choosing first available camera.
        if (targetDeviceName == null && fallback) {
            targetDeviceName = findCamera { true }
        }

        if (targetDeviceName == null) {
            return null
        }

        return targetDeviceName
    }

    /**
     * Finds the device id of a camera that matches the [predicate].
     */
    fun CameraEnumerator.findCamera(predicate: (deviceName: String) -> Boolean): String? {
        for (deviceName in deviceNames) {
            if (predicate(deviceName)) {
                return deviceName
            }
        }
        return null
    }

    /**
     * Returns the camera position of a camera, or null if neither front or back facing (e.g. external camera).
     */
    fun CameraEnumerator.getCameraPosition(deviceName: String?): CameraPosition? {
        if (deviceName == null) {
            return null
        }
        if (isBackFacing(deviceName)) {
            return CameraPosition.BACK
        } else if (isFrontFacing(deviceName)) {
            return CameraPosition.FRONT
        }
        return null
    }

    /**
     * An interface declaring a provider of camera capturers.
     */
    interface CameraProvider {
        /**
         * This acts as the priority of the CameraProvider when determining which provider to use (in order of highest to lowest).
         */
        val cameraVersion: Int
        fun provideEnumerator(context: Context): CameraEnumerator
        fun provideCapturer(context: Context, options: LocalVideoTrackOptions, eventsHandler: CameraEventsDispatchHandler): VideoCapturer

        /**
         * If the return value of this method is false, this provider will be skipped when querying providers to use.
         */
        fun isSupported(context: Context): Boolean
    }
}
