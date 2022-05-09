package io.livekit.android.videoencodedecode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.livekit.android.room.util.createAnswer
import io.livekit.android.room.util.createOffer
import io.livekit.android.room.util.setLocalDescription
import io.livekit.android.room.util.setRemoteDescription
import io.livekit.android.util.Either
import kotlinx.coroutines.launch
import org.webrtc.MediaConstraints
import org.webrtc.VideoTrack

class P2PCallViewModel(
    private val useDefaultVideoEncoder: Boolean = false,
    private val codecWhiteList: List<String>? = null,
    application: Application
) : AndroidViewModel(application) {
    val streamer = Streamer(
        useDefaultVideoEncoder = useDefaultVideoEncoder,
        codecWhiteList = codecWhiteList,
        application = application,
    )

    val receiver = Receiver(
        application = application,
        onAddTrack = { track ->
            if (track is VideoTrack) {
                videoTrack.postValue(track)
            }
        }
    )

    val videoTrack = MutableLiveData<VideoTrack>()

    init {
        viewModelScope.launch {
            val streamerOffer = when (val outcome = streamer.peerConnection.createOffer(MediaConstraints())) {
                is Either.Left -> outcome.value
                is Either.Right -> throw RuntimeException()
            }
            streamer.peerConnection.setLocalDescription(streamerOffer)
            receiver.peerConnection.setRemoteDescription(streamerOffer)
            val receiverAnswer = when (val outcome = receiver.peerConnection.createAnswer(MediaConstraints())) {
                is Either.Left -> outcome.value
                is Either.Right -> throw RuntimeException()
            }
            receiver.peerConnection.setLocalDescription(receiverAnswer)
            streamer.peerConnection.setRemoteDescription(receiverAnswer)
        }
    }
}