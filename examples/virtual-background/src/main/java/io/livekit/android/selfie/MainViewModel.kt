/*
 * Copyright 2024-2025 LiveKit, Inc.
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

package io.livekit.android.selfie

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.OptIn
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.track.processing.video.VirtualBackgroundVideoProcessor
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.EglBase

@OptIn(ExperimentalCamera2Interop::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    init {
        LiveKit.loggingLevel = LoggingLevel.INFO
    }

    val eglBase = EglBase.create()
    val room = LiveKit.create(
        application,
        overrides = LiveKitOverrides(
            eglBase = eglBase,
        ),
    )

    val processor = VirtualBackgroundVideoProcessor(eglBase, Dispatchers.IO).apply {
        val drawable = AppCompatResources.getDrawable(application, R.drawable.background) as BitmapDrawable
        backgroundImage = drawable.bitmap
    }

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private var imageAnalysis = ImageAnalysis.Builder().build()
        .apply { setAnalyzer(Dispatchers.IO.asExecutor(), processor.imageAnalyzer) }

    init {
        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get(), arrayOf(imageAnalysis)).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
            }
        }
    }

    val track = MutableLiveData<LocalVideoTrack?>(null)

    fun startCapture() {
        val videoTrack = room.localParticipant.createVideoTrack(
            options = LocalVideoTrackOptions(position = CameraPosition.FRONT),
            videoProcessor = processor,
        )

        videoTrack.startCapture()
        track.postValue(videoTrack)
    }

    override fun onCleared() {
        super.onCleared()
        track.value?.stopCapture()
        room.release()
        processor.dispose()
        cameraProvider?.let {
            CameraCapturerUtils.unregisterCameraProvider(it)
        }
    }

    fun toggleProcessor(): Boolean {
        val newState = !processor.enabled
        processor.enabled = newState
        return newState
    }
}
