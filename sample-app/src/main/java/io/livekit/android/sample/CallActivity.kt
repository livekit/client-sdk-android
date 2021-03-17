package io.livekit.android.sample

import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.CallActivityBinding
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    lateinit var binding: CallActivityBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
        if (args == null) {
            finish()
            return
        }

        lifecycleScope.launch {

            val room = LiveKit.connect(
                applicationContext,
                args.url,
                args.token,
                ConnectOptions(false),
                object : Room.Listener {

                    var loadedParticipant = false
                    override fun onConnect(room: Room) {
                    }

                    override fun onDisconnect(room: Room, error: Exception?) {
                    }

                    override fun onParticipantConnected(
                        room: Room,
                        participant: RemoteParticipant
                    ) {
                        if (!loadedParticipant) {
                            room.setupVideo(binding.fullscreenVideoView)
                            participant.remoteVideoTracks
                                .first()
                                .track
                                .let { it as? VideoTrack }
                                ?.addRenderer(binding.fullscreenVideoView)
                        }
                    }

                    override fun onParticipantDisconnected(
                        room: Room,
                        participant: RemoteParticipant
                    ) {
                    }

                    override fun onFailedToConnect(room: Room, error: Exception) {
                    }

                    override fun onReconnecting(room: Room, error: Exception) {
                    }

                    override fun onReconnect(room: Room) {
                    }

                    override fun onStartRecording(room: Room) {
                    }

                    override fun onStopRecording(room: Room) {
                    }

                    override fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {
                    }

                }
            )
        }
    }


    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}