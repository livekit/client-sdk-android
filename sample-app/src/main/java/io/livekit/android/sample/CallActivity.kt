package io.livekit.android.sample

import android.media.AudioManager
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import com.github.ajalt.timberkt.Timber
import com.google.android.material.tabs.TabLayoutMediator
import com.snakydesign.livedataextensions.combineLatest
import com.xwray.groupie.GroupieAdapter
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.sample.databinding.CallActivityBinding
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    lateinit var binding: CallActivityBinding
    var tabLayoutMediator: TabLayoutMediator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        // Viewpager setup
        val adapter = GroupieAdapter()

        binding.viewPager.apply {
            this.adapter = adapter
        }

        combineLatest(
            viewModel.room,
            viewModel.remoteParticipants
        ) { room, participants -> room to participants }
            .observe(this) {

                tabLayoutMediator?.detach()
                tabLayoutMediator = null

                val (room, participants) = it
                val items = participants.map { participant -> ParticipantItem(room, participant) }
                adapter.update(items)

                tabLayoutMediator =
                    TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
                        tab.text = participants[position].identity
                    }
                tabLayoutMediator?.attach()
            }

        viewModel.room.observe(this) { room ->
            room.initVideoRenderer(binding.pipVideoView)
            val localParticipant = room.localParticipant
            if (localParticipant != null) {
                val videoTrack = localParticipant.localVideoTrackPublications
                    .firstOrNull()
                    ?.track as? LocalVideoTrack
                videoTrack?.addRenderer(binding.pipVideoView)
            }
        }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        with(audioManager) {
            isSpeakerphoneOn = true
            isMicrophoneMute = false
            mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val result = audioManager.requestAudioFocus(
            { },
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.v { "Audio focus request granted for VOICE_CALL streams" }
        } else {
            Timber.v { "Audio focus request failed" }
        }
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}