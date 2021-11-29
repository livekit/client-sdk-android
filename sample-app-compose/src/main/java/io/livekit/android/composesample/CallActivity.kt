package io.livekit.android.composesample

import android.app.Activity
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.github.ajalt.timberkt.Timber
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.sample.CallViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.parcelize.Parcelize

@OptIn(ExperimentalPagerApi::class)
class CallActivity : AppCompatActivity() {

    private val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener {}

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
            viewModel.startScreenCapture(data)
        }


    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContent {
            val room by viewModel.room.collectAsState()
            val participants by viewModel.participants.collectAsState(initial = emptyList())
            val primarySpeaker by viewModel.primarySpeaker.collectAsState()
            val activeSpeakers by viewModel.activeSpeakers.collectAsState(initial = emptyList())
            val micEnabled by viewModel.micEnabled.observeAsState(true)
            val videoEnabled by viewModel.cameraEnabled.observeAsState(true)
            val flipButtonEnabled by viewModel.flipButtonVideoEnabled.observeAsState(true)
            val screencastEnabled by viewModel.screenshareEnabled.observeAsState(false)
            Content(
                room,
                participants,
                primarySpeaker,
                activeSpeakers,
                micEnabled,
                videoEnabled,
                flipButtonEnabled,
                screencastEnabled,
            )
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    val previewParticipant = Participant("asdf", "asdf", Dispatchers.Main)

    @ExperimentalMaterialApi
    @Preview(showBackground = true, showSystemUi = true)
    @Composable
    fun Content(
        room: Room? = null,
        participants: List<Participant> = listOf(previewParticipant),
        primarySpeaker: Participant? = previewParticipant,
        activeSpeakers: List<Participant> = listOf(previewParticipant),
        micEnabled: Boolean = true,
        videoEnabled: Boolean = true,
        flipButtonEnabled: Boolean = true,
        screencastEnabled: Boolean = false,
    ) {
        AppTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                val (speakerView, audienceRow, buttonBar) = createRefs()

                Surface(modifier = Modifier.constrainAs(speakerView) {
                    top.linkTo(parent.top)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                    bottom.linkTo(audienceRow.top)
                    width = Dimension.fillToConstraints
                    height = Dimension.fillToConstraints
                }) {
                    if (room != null && primarySpeaker != null) {
                        ParticipantItem(
                            room = room,
                            participant = primarySpeaker,
                            isSpeaking = activeSpeakers.contains(primarySpeaker)
                        )
                    }
                }
                LazyRow(
                    modifier = Modifier
                        .constrainAs(audienceRow) {
                            top.linkTo(speakerView.bottom)
                            bottom.linkTo(buttonBar.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            width = Dimension.fillToConstraints
                            height = Dimension.value(120.dp)
                        }
                ) {
                    if (room != null) {
                        items(
                            count = participants.size,
                            key = { index -> participants[index].sid }
                        ) { index ->
                            ParticipantItem(
                                room = room,
                                participant = participants[index],
                                isSpeaking = activeSpeakers.contains(participants[index]),
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1.0f, true)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 20.dp)
                        .fillMaxWidth()
                        .constrainAs(buttonBar) {
                            bottom.linkTo(parent.bottom)
                            width = Dimension.fillToConstraints
                            height = Dimension.wrapContent
                        },
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Surface(
                        onClick = { viewModel.setMicEnabled(!micEnabled) },
                    ) {
                        val resource =
                            if (micEnabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                        Icon(
                            painterResource(id = resource),
                            contentDescription = "Mic",
                            tint = Color.White,
                        )
                    }
                    Surface(
                        onClick = { viewModel.setCameraEnabled(!videoEnabled) },
                    ) {
                        val resource =
                            if (videoEnabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                        Icon(
                            painterResource(id = resource),
                            contentDescription = "Video",
                            tint = Color.White,
                        )
                    }
                    Surface(
                        onClick = { viewModel.flipCamera() },
                    ) {
                        Icon(
                            painterResource(id = R.drawable.outline_flip_camera_android_24),
                            contentDescription = "Flip Camera",
                            tint = Color.White,
                        )
                    }
                    Surface(
                        onClick = {
                            if (!screencastEnabled) {
                                requestMediaProjection()
                            } else {
                                viewModel.stopScreenCapture()
                            }
                        },
                    ) {
                        val resource =
                            if (screencastEnabled) R.drawable.baseline_cast_connected_24 else R.drawable.baseline_cast_24
                        Icon(
                            painterResource(id = resource),
                            contentDescription = "Flip Camera",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

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