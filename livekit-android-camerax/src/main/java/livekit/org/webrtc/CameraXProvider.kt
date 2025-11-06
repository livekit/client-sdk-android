/*
 * Copyright 2025 LiveKit, Inc.
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
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.UseCase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraCapturerUtils.findCamera
import io.livekit.android.room.track.video.CameraEventsDispatchHandler

@ExperimentalCamera2Interop
class CameraXProvider(
    val lifecycleOwner: LifecycleOwner,
    val useCases: Array<out UseCase> = emptyArray(),
    override val cameraVersion: Int = 3,
) : CameraCapturerUtils.CameraProvider {

    var enumerator: CameraXEnumerator? = null
        private set

    override fun provideEnumerator(context: Context): CameraXEnumerator = enumerator ?: CameraXEnumerator(context, lifecycleOwner, useCases).also {
        enumerator = it
    }

    override fun provideCapturer(
        context: Context,
        options: LocalVideoTrackOptions,
        eventsHandler: CameraEventsDispatchHandler,
    ): VideoCapturer {
        val enumerator = provideEnumerator(context)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val targetDevice = enumerator.findCamera(options.deviceId, options.position)
        val targetDeviceId = targetDevice?.deviceId

        val targetVideoCapturer = enumerator.createCapturer(targetDeviceId, eventsHandler) as CameraXCapturer

        return CameraXCapturerWithSize(
            targetVideoCapturer,
            cameraManager,
            targetDeviceId,
            eventsHandler,
        )
    }

    override fun isSupported(context: Context): Boolean {
        return Camera2Enumerator.isSupported(context) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
    }
}
