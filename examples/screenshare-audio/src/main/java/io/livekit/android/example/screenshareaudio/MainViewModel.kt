package io.livekit.android.example.screenshareaudio

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.livekit.android.LiveKit
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.sample.service.ForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import livekit.org.webrtc.ScreenCapturerAndroid


val url = "wss://example.com"
val token = ""


@RequiresApi(Build.VERSION_CODES.Q)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val room = LiveKit.create(application).apply {

    }
    var audioCapturer: ScreenAudioCapturer? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            room.connect(url, token)
        }

        // Start a foreground service to keep the call from being interrupted if the
        // app goes into the background.
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        application.startForegroundService(foregroundServiceIntent)
    }

    fun startScreenCapture(data: Intent) {
        viewModelScope.launch(Dispatchers.IO) {
            room.localParticipant.setScreenShareEnabled(true, data)
            room.localParticipant.setMicrophoneEnabled(true)
            val screenCaptureTrack = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack ?: return@launch
            val screenCapturer = screenCaptureTrack.capturer as? ScreenCapturerAndroid ?: return@launch
            val mediaProjection = screenCapturer.mediaProjection ?: return@launch

            val audioTrack = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack ?: return@launch

            audioCapturer = ScreenAudioCapturer(mediaProjection)
            audioTrack.setAudioBufferCallback(audioCapturer!!)
        }
    }


    fun stopScreenCapture() {

        viewModelScope.launch(Dispatchers.IO) {

            audioCapturer?.releaseAudioResources()
            (room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack)
                ?.setAudioBufferCallback(null)
            room.localParticipant.setMicrophoneEnabled(false)
            room.localParticipant.setScreenShareEnabled(false)
        }
    }
}
