package io.livekit.android.sample

import android.app.Activity
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.ajalt.timberkt.Timber
import com.snakydesign.livedataextensions.combineLatest
import com.snakydesign.livedataextensions.scan
import com.snakydesign.livedataextensions.take
import com.xwray.groupie.GroupieAdapter
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.CallActivityBinding
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    lateinit var binding: CallActivityBinding
    val focusChangeListener = AudioManager.OnAudioFocusChangeListener {}

    private var previousSpeakerphoneOn = true
    private var previousMicrophoneMute = false

    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != Activity.RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            viewModel.setScreenshare(true, data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        // Audience row setup
        binding.audienceRow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val adapter = GroupieAdapter()

        binding.audienceRow.apply {
            this.adapter = adapter
        }

        combineLatest(
            viewModel.room,
            viewModel.participants
        ) { room, participants -> room to participants }
            .observe(this) {

                val (room, participants) = it
                val items = participants.map { participant -> ParticipantItem(room, participant) }
                adapter.update(items)
            }

        // speaker view setup
        viewModel.room.take(1).observe(this) { room ->
            room.initVideoRenderer(binding.speakerView)
            viewModel.activeSpeaker
                .scan(Pair<Participant?, Participant?>(null, null)) { pair, participant ->
                    // old participant is first
                    // latest active participant is second
                    Pair(pair.second, participant)
                }.observe(this) { (oldSpeaker, newSpeaker) ->
                    // Remove any renderering from the old speaker
                    oldSpeaker?.videoTracks
                        ?.values
                        ?.forEach { trackPublication ->
                            (trackPublication.track as? VideoTrack)?.removeRenderer(binding.speakerView)
                        }

                    val videoTrack = newSpeaker?.videoTracks?.values
                        ?.firstOrNull()
                        ?.track as? VideoTrack
                    videoTrack?.addRenderer(binding.speakerView)
                }
        }

        // Controls setup
        viewModel.videoEnabled.observe(this) { enabled ->
            binding.camera.setOnClickListener { viewModel.setCameraEnabled(!enabled) }
            binding.camera.setImageResource(
                if (enabled) R.drawable.outline_videocam_24
                else R.drawable.outline_videocam_off_24
            )
            binding.flipCamera.isEnabled = enabled
        }
        viewModel.micEnabled.observe(this) { enabled ->
            binding.mic.setOnClickListener { viewModel.setMicEnabled(!enabled) }
            binding.mic.setImageResource(
                if (enabled) R.drawable.outline_mic_24
                else R.drawable.outline_mic_off_24
            )
        }

        binding.flipCamera.setOnClickListener { viewModel.flipCamera() }
        viewModel.screenshareEnabled.observe(this) { enabled ->
            binding.screenShare.setOnClickListener {
                if (enabled) {
                    viewModel.setScreenshare(!enabled)
                } else {
                    requestMediaProjection()
                }
            }
            binding.screenShare.setImageResource(
                if (enabled) R.drawable.baseline_cast_connected_24
                else R.drawable.baseline_cast_24
            )
        }

        // Grab audio focus for video call
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        with(audioManager) {
            previousSpeakerphoneOn = isSpeakerphoneOn
            previousMicrophoneMute = isMicrophoneMute
            isSpeakerphoneOn = true
            isMicrophoneMute = false
            mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val result = audioManager.requestAudioFocus(
            focusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN,
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.v { "Audio focus request granted for VOICE_CALL streams" }
        } else {
            Timber.v { "Audio focus request failed" }
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()

        // Release video views
        binding.speakerView.release()

        // Undo audio mode changes
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        with(audioManager) {
            isSpeakerphoneOn = previousSpeakerphoneOn
            isMicrophoneMute = previousMicrophoneMute
            abandonAudioFocus(focusChangeListener)
            mode = AudioManager.MODE_NORMAL
        }
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}