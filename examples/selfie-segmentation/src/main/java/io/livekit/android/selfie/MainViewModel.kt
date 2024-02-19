package io.livekit.android.selfie

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import kotlinx.coroutines.Dispatchers
import livekit.org.webrtc.EglBase

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val eglBase = EglBase.create()
    val room = LiveKit.create(
        application,
        overrides = LiveKitOverrides(
            eglBase = eglBase,
        ),
    )

    val track = MutableLiveData<LocalVideoTrack?>(null)

    // For direct I420 processing:
    // val processor = SelfieVideoProcessor()
    val processor = SelfieBitmapVideoProcessor(eglBase, Dispatchers.IO)

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
    }
}
