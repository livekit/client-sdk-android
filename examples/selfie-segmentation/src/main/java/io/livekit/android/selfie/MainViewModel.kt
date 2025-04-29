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

package io.livekit.android.selfie

import android.app.Application
import android.os.Build
import android.util.Size
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.EglBase

@OptIn(ExperimentalCamera2Interop::class)
@RequiresApi(Build.VERSION_CODES.M)
class MainViewModel constructor
    (application: Application) : AndroidViewModel(application) {

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

    // For direct I420 processing:
    val processor = ShaderBitmapVideoProcessor(eglBase, Dispatchers.IO)
    //val processor = SelfieVideoProcessor(Dispatchers.IO)
    //val processor = SelfieBitmapVideoProcessor(eglBase, Dispatchers.IO)

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private var imageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build(),
        )
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .apply {
            setAnalyzer(Dispatchers.IO.asExecutor(), processor.imageAnalyzer)
        }

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
        val selfieVideoTrack = room.localParticipant.createVideoTrack(
            options = LocalVideoTrackOptions(position = CameraPosition.FRONT),
            videoProcessor = processor,
        )

        selfieVideoTrack.startCapture()
        track.postValue(selfieVideoTrack)
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
}
