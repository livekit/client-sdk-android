package io.livekit.android.composesample

import android.media.AudioManager
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.github.ajalt.timberkt.Timber
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.pagerTabIndicatorOffset
import com.google.accompanist.pager.rememberPagerState
import com.google.android.material.tabs.TabLayoutMediator
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.parcelize.Parcelize

@OptIn(ExperimentalPagerApi::class)
class CallActivity : AppCompatActivity() {

    val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    var tabLayoutMediator: TabLayoutMediator? = null
    val focusChangeListener = AudioManager.OnAudioFocusChangeListener {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        with(audioManager) {
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
                Content(room, participants)
            }
        }
    }

    @Preview(showBackground = true, showSystemUi = true)
    @Composable
    fun Content(
        room: Room? = null,
        participants: List<RemoteParticipant> = emptyList()
    ){
        Surface (
            modifier = Modifier.fillMaxSize()
        ) {
            Column {
                val pagerState = rememberPagerState()
                if(participants.isNotEmpty()) {
                    TabRow(
                        // Our selected tab is our current page
                        selectedTabIndex = pagerState.currentPage,
                        // Override the indicator, using the provided pagerTabIndicatorOffset modifier
                        indicator = { tabPositions ->
                            TabRowDefaults.Indicator(
                                Modifier.pagerTabIndicatorOffset(pagerState, tabPositions)
                            )
                        }
                    ) {
                        // Add tabs for all of our pages
                        participants.forEachIndexed { index, participant ->
                            Tab(
                                text = { Text(participant.identity ?: "Unnamed $index") },
                                selected = pagerState.currentPage == index,
                                onClick = { /* TODO */ },
                            )
                        }
                    }
                    HorizontalPager(count = participants.size) { index ->
                        if (room != null) {
                            ParticipantItem(room = room, participant = participants[index])
                        }
                    }

                    Row (horizontalArrangement = Arrangement.SpaceEvenly){
                        IconButton(onClick = { /*TODO*/ }) {
                            Icon(painterResource(id = R.drawable.outline_mic_24), "Mic")
                        }
                        IconButton(onClick = { /*TODO*/ }) {
                            Icon(painterResource(id = R.drawable.outline_videocam_24), "Video")
                        }
                        IconButton(onClick = { /*TODO*/ }) {
                            Icon(painterResource(id = R.drawable.outline_flip_camera_android_24), "Flip Camera")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        with(audioManager) {
            isSpeakerphoneOn = false
            isMicrophoneMute = true
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