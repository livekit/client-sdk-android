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

package io.livekit.android.test.mock.camera

import android.content.Context
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.room.track.video.CameraEventsDispatchHandler
import livekit.org.webrtc.CameraEnumerationAndroid
import livekit.org.webrtc.CameraEnumerator
import livekit.org.webrtc.CameraVideoCapturer
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer

class MockCameraProvider : CameraCapturerUtils.CameraProvider {

    companion object {
        fun register() {
            CameraCapturerUtils.registerCameraProvider(MockCameraProvider())
        }
    }

    private val enumerator by lazy { MockCameraEnumerator() }

    override val cameraVersion: Int = 100

    override fun provideEnumerator(context: Context): CameraEnumerator {
        return enumerator
    }

    override fun provideCapturer(context: Context, options: LocalVideoTrackOptions, eventsHandler: CameraEventsDispatchHandler): VideoCapturer {
        return enumerator.createCapturer(options.deviceId, eventsHandler)
    }

    override fun isSupported(context: Context): Boolean {
        return true
    }
}

class MockCameraEnumerator : CameraEnumerator {
    override fun getDeviceNames(): Array<String> {
        return arrayOf("camera")
    }

    override fun isFrontFacing(deviceName: String): Boolean {
        return true
    }

    override fun isBackFacing(deviceName: String): Boolean {
        return false
    }

    override fun getSupportedFormats(p0: String): MutableList<CameraEnumerationAndroid.CaptureFormat> {
        return mutableListOf(
            CameraEnumerationAndroid.CaptureFormat(480, 640, 30, 30),
        )
    }

    override fun createCapturer(deviceName: String?, eventsHandler: CameraVideoCapturer.CameraEventsHandler): CameraVideoCapturer {
        return MockCameraVideoCapturer()
    }
}

class MockCameraVideoCapturer : CameraVideoCapturer {
    override fun initialize(p0: SurfaceTextureHelper?, p1: Context?, p2: CapturerObserver?) {
    }

    override fun startCapture(p0: Int, p1: Int, p2: Int) {
    }

    override fun stopCapture() {
    }

    override fun changeCaptureFormat(p0: Int, p1: Int, p2: Int) {
    }

    override fun dispose() {
    }

    override fun isScreencast(): Boolean = false

    override fun switchCamera(p0: CameraVideoCapturer.CameraSwitchHandler?) {
    }

    override fun switchCamera(p0: CameraVideoCapturer.CameraSwitchHandler?, p1: String?) {
    }
}
