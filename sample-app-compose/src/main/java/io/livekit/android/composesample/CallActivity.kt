package io.livekit.android.composesample

import android.media.AudioManager
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.github.ajalt.timberkt.Timber
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.rememberPagerState
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
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
            AppTheme(darkTheme = true) {
                val room by viewModel.room.observeAsState()
                val participants by viewModel.remoteParticipants.observeAsState(emptyList())
                val micEnabled by viewModel.micEnabled.observeAsState(true)
                val videoEnabled by viewModel.videoEnabled.observeAsState(true)
                val flipButtonEnabled by viewModel.flipButtonVideoEnabled.observeAsState(true)
                Content(
                    room,
                    participants,
                    micEnabled,
                    videoEnabled,
                    flipButtonEnabled
                )
            }
        }
    }

    @Preview(showBackground = true, showSystemUi = true)
    @Composable
    fun Content(
        room: Room? = null,
        participants: List<RemoteParticipant> = emptyList(),
        micEnabled: Boolean = true,
        videoEnabled: Boolean = true,
        flipButtonEnabled: Boolean = true,
    ) {
        ConstraintLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            val (tabRow, pager, buttonBar, cameraView) = createRefs()

            if (participants.isNotEmpty()) {
                val pagerState = rememberPagerState()
                ScrollableTabRow(
                    // Our selected tab is our current page
                    selectedTabIndex = pagerState.currentPage,
                    // Override the indicator, using the provided pagerTabIndicatorOffset modifier
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier
                                .height(1.dp)
                                .tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            height = 1.dp,
                            color = Color.Gray
                        )
                    },
                    modifier = Modifier
                        .background(Color.DarkGray)
                        .constrainAs(tabRow) {
                            top.linkTo(parent.top)
                            width = Dimension.fillToConstraints
                        }
                ) {
                    // Add tabs for all of our pages
                    participants.forEachIndexed { index, participant ->
                        Tab(
                            text = { Text(participant.identity ?: "Unnamed $index") },
                            selected = pagerState.currentPage == index,
                            onClick = { /* TODO*/ },
                        )
                    }
                }
                HorizontalPager(
                    count = participants.size,
                    state = pagerState,
                    modifier = Modifier
                        .constrainAs(pager) {
                            top.linkTo(tabRow.bottom)
                            bottom.linkTo(buttonBar.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                ) { index ->
                    if (room != null) {
                        ParticipantItem(room = room, participant = participants[index])
                    }
                }
            }

            if (room != null) {
                var videoNeedsSetup by remember { mutableStateOf(true) }
                AndroidView(
                    factory = { context ->
                        TextureViewRenderer(context).apply {
                            room.initVideoRenderer(this)
                        }
                    },
                    modifier = Modifier
                        .width(200.dp)
                        .height(200.dp)
                        .padding(bottom = 10.dp, end = 10.dp)
                        .background(Color.Black)
                        .constrainAs(cameraView) {
                            bottom.linkTo(buttonBar.top)
                            end.linkTo(parent.end)
                        },
                    update = { view ->
                        val videoTrack = room.localParticipant.videoTracks.values
                            .firstOrNull()
                            ?.track as? LocalVideoTrack

                        if (videoNeedsSetup) {
                            videoTrack?.addRenderer(view)
                            videoNeedsSetup = false
                        }
                    }
                )
            }
            Row(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 20.dp)
                    .fillMaxWidth()
                    .constrainAs(buttonBar) {
                        bottom.linkTo(parent.bottom)
                        width = Dimension.fillToConstraints
                    },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                FloatingActionButton(
                    onClick = { viewModel.setMicEnabled(!micEnabled) },
                    backgroundColor = Color.DarkGray,
                ) {
                    val resource =
                        if (micEnabled) R.drawable.outline_mic_24 else R.drawable.outline_mic_off_24
                    Icon(
                        painterResource(id = resource),
                        contentDescription = "Mic",
                        tint = Color.White,
                    )
                }
                FloatingActionButton(
                    onClick = { viewModel.setVideoEnabled(!videoEnabled) },
                    backgroundColor = Color.DarkGray,
                ) {
                    val resource =
                        if (videoEnabled) R.drawable.outline_videocam_24 else R.drawable.outline_videocam_off_24
                    Icon(
                        painterResource(id = resource),
                        contentDescription = "Video",
                        tint = Color.White,
                    )
                }
                FloatingActionButton(
                    onClick = { viewModel.flipVideo() },
                    backgroundColor = Color.DarkGray,
                ) {
                    Icon(
                        painterResource(id = R.drawable.outline_flip_camera_android_24),
                        contentDescription = "Flip Camera",
                        tint = Color.White,
                    )
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