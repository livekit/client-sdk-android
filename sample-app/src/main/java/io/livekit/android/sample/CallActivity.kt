package io.livekit.android.sample

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xwray.groupie.GroupieAdapter
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.sample.databinding.CallActivityBinding
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.*
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    lateinit var binding: CallActivityBinding
    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != Activity.RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            viewModel.startScreenCapture(data)
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

        lifecycleScope.launchWhenCreated {
            viewModel.room
                .combine(viewModel.participants) { room, participants -> room to participants }
                .collect { (room, participants) ->
                    if (room != null) {
                        val items = participants.map { participant -> ParticipantItem(room, participant) }
                        adapter.update(items)
                    }
                }
        }

        // speaker view setup
        lifecycleScope.launchWhenCreated {
            viewModel.room.filterNotNull().take(1)
                .transform { room ->
                    // Initialize video renderer
                    room.initVideoRenderer(binding.speakerVideoView)

                    // Observe primary speaker changes
                    emitAll(viewModel.primarySpeaker)
                }.flatMapLatest { primarySpeaker ->
                    // Update new primary speaker identity
                    binding.identityText.text = primarySpeaker?.identity

                    if (primarySpeaker != null) {
                        primarySpeaker::audioTracks.flow
                            .flatMapLatest { tracks ->
                                val audioTrack = tracks.firstOrNull()?.first
                                if (audioTrack != null) {
                                    audioTrack::muted.flow
                                } else {
                                    flowOf(true)
                                }
                            }
                            .collect { muted ->
                                binding.muteIndicator.visibility = if (muted) View.VISIBLE else View.INVISIBLE
                            }
                    }

                    // observe videoTracks changes.
                    if (primarySpeaker != null) {
                        primarySpeaker::videoTracks.flow
                            .map { primarySpeaker to it }
                    } else {
                        emptyFlow()
                    }
                }.flatMapLatest { (participant, videoTracks) ->

                    // Prioritize any screenshare streams.
                    val trackPublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                        ?: participant.getTrackPublication(Track.Source.CAMERA)
                        ?: videoTracks.firstOrNull()?.first
                        ?: return@flatMapLatest emptyFlow()

                    trackPublication::track.flow
                }.collect { videoTrack ->
                    // Cleanup old video track
                    val oldVideoTrack = binding.speakerVideoView.tag as? VideoTrack
                    oldVideoTrack?.removeRenderer(binding.speakerVideoView)

                    // Bind new video track to video view.
                    if (videoTrack is VideoTrack) {
                        videoTrack.addRenderer(binding.speakerVideoView)
                        binding.speakerVideoView.visibility = View.VISIBLE
                    } else {
                        binding.speakerVideoView.visibility = View.INVISIBLE
                    }
                    binding.speakerVideoView.tag = videoTrack
                }
        }

        // Controls setup
        viewModel.cameraEnabled.observe(this) { enabled ->
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
                    viewModel.stopScreenCapture()
                } else {
                    requestMediaProjection()
                }
            }
            binding.screenShare.setImageResource(
                if (enabled) R.drawable.baseline_cast_connected_24
                else R.drawable.baseline_cast_24
            )
        }

        binding.message.setOnClickListener {
            val editText = EditText(this)
            AlertDialog.Builder(this)
                .setTitle("Send Message")
                .setView(editText)
                .setPositiveButton("Send") { dialog, _ ->
                    viewModel.sendData(editText.text?.toString() ?: "")
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .create()
                .show()
        }

        binding.exit.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launchWhenResumed {
            viewModel.error.collect {
                if (it != null) {
                    Toast.makeText(this@CallActivity, "Error: $it", Toast.LENGTH_LONG).show()
                    viewModel.dismissError()
                }
            }
        }

        lifecycleScope.launchWhenResumed {
            viewModel.dataReceived.collect {
                Toast.makeText(this@CallActivity, "Data received: $it", Toast.LENGTH_LONG).show()
            }
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
        binding.speakerVideoView.release()
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}