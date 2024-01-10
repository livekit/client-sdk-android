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

    /**
     * Create a CameraEnumerator based on platform capabilities.
     *
     * If available, creates an enumerator that uses Camera2. If not,
     * a Camera1 enumerator is created.
     */
    fun createCameraEnumerator(context: Context): CameraEnumerator {
        return if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
    }

    /**
     * Creates a Camera capturer.
     */
    fun createCameraCapturer(
        context: Context,
        options: LocalVideoTrackOptions,
    ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
        val cameraEnumerator = createCameraEnumerator(context)
        val pair = createCameraCapturer(context, cameraEnumerator, options)

        if (pair == null) {
            LKLog.d { "Failed to open camera" }
            return null
        }
        return pair
    }

    private fun createCameraCapturer(
        context: Context,
        enumerator: CameraEnumerator,
        options: LocalVideoTrackOptions,
    ): Pair<VideoCapturer, LocalVideoTrackOptions>? {
        val cameraEventsDispatchHandler = CameraEventsDispatchHandler()
        val targetDeviceName = enumerator.findCamera(options.deviceId, options.position) ?: return null
        val targetVideoCapturer = enumerator.createCapturer(targetDeviceName, cameraEventsDispatchHandler)

        // back fill any missing information
        val newOptions = options.copy(
            deviceId = targetDeviceName,
            position = enumerator.getCameraPosition(targetDeviceName),
        )
        if (targetVideoCapturer is Camera1Capturer) {
            // Cache supported capture formats ahead of time to avoid future camera locks.
            Camera1Helper.getSupportedFormats(Camera1Helper.getCameraId(newOptions.deviceId))
            return Pair(
                Camera1CapturerWithSize(
                    targetVideoCapturer,
                    targetDeviceName,
                    cameraEventsDispatchHandler,
                ),
                newOptions,
            )
        }

        if (targetVideoCapturer is Camera2Capturer) {
            return Pair(
                Camera2CapturerWithSize(
                    targetVideoCapturer,
                    context.getSystemService(Context.CAMERA_SERVICE) as CameraManager,
                    targetDeviceName,
                    cameraEventsDispatchHandler,
                ),
                newOptions,
            )
        }

        LKLog.w { "unknown CameraCapturer class: ${targetVideoCapturer.javaClass.canonicalName}. Reported dimensions may be inaccurate." }
        if (targetVideoCapturer != null) {
            return Pair(
                targetVideoCapturer,
                newOptions,
            )
        }

        return null
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
}
