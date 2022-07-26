package io.livekit.android.composesample

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.*
import androidx.compose.material.ripple.rememberRipple
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
import androidx.lifecycle.lifecycleScope
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.composesample.ui.DebugMenuDialog
import io.livekit.android.composesample.ui.SelectAudioDeviceDialog
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.sample.CallViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    private val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Setup compose view.
        setContent {
            val room = viewModel.room
            val participants by viewModel.participants.collectAsState(initial = emptyList())
            val primarySpeaker by viewModel.primarySpeaker.collectAsState()
            val activeSpeakers by viewModel.activeSpeakers.collectAsState(initial = emptyList())
            val micEnabled by viewModel.micEnabled.observeAsState(true)
            val videoEnabled by viewModel.cameraEnabled.observeAsState(true)
            val flipButtonEnabled by viewModel.flipButtonVideoEnabled.observeAsState(true)
            val screencastEnabled by viewModel.screenshareEnabled.observeAsState(false)
            val permissionAllowed by viewModel.permissionAllowed.collectAsState()
            Content(
                room,
                participants,
                primarySpeaker,
                activeSpeakers,
                micEnabled,
                videoEnabled,
                flipButtonEnabled,
                screencastEnabled,
                audioSwitchHandler = viewModel.audioHandler,
                permissionAllowed = permissionAllowed,
                onExitClick = { finish() },
                onSendMessage = { viewModel.sendData(it) },
                onSimulateMigration = { viewModel.simulateMigration() },
                fullReconnect = { viewModel.reconnect() },
            )
        }
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
        permissionAllowed: Boolean = true,
        audioSwitchHandler: AudioSwitchHandler? = null,
        onExitClick: () -> Unit = {},
        error: Throwable? = null,
        onSnackbarDismiss: () -> Unit = {},
        onSendMessage: (String) -> Unit = {},
        onSimulateMigration: () -> Unit = {},
        fullReconnect: () -> Unit = {},
    ) {
        AppTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                val (speakerView, audienceRow, buttonBar) = createRefs()

                // Primary speaker view
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

                // Audience row to display all participants.
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

                // Control bar for any switches such as mic/camera enable/disable.
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 20.dp)
                        .fillMaxWidth()
                        .constrainAs(buttonBar) {
                            bottom.linkTo(parent.bottom)
                            width = Dimension.fillToConstraints
                            height = Dimension.wrapContent
                        },
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    val controlSize = 40.dp
                    val controlPadding = 4.dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Surface(
                            onClick = { viewModel.setMicEnabled(!micEnabled) },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
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
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
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
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
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
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            val resource =
                                if (screencastEnabled) R.drawable.baseline_cast_connected_24 else R.drawable.baseline_cast_24
                            Icon(
                                painterResource(id = resource),
                                contentDescription = "Flip Camera",
                                tint = Color.White,
                            )
                        }

                        var showMessageDialog by remember { mutableStateOf(false) }
                        var messageToSend by remember { mutableStateOf("") }
                        Surface(
                            onClick = { showMessageDialog = true },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            Icon(
                                painterResource(id = R.drawable.baseline_chat_24),
                                contentDescription = "Send Message",
                                tint = Color.White,
                            )
                        }

                        if (showMessageDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    showMessageDialog = false
                                    messageToSend = ""
                                },
                                title = {
                                    Text(text = "Send Message")
                                },
                                text = {
                                    OutlinedTextField(
                                        value = messageToSend,
                                        onValueChange = { messageToSend = it },
                                        label = { Text("Message") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            onSendMessage(messageToSend)
                                            showMessageDialog = false
                                            messageToSend = ""
                                        }
                                    ) { Text("Send") }
                                },
                                dismissButton = {
                                    Button(
                                        onClick = {
                                            showMessageDialog = false
                                            messageToSend = ""
                                        }
                                    ) { Text("Cancel") }
                                },
                                backgroundColor = Color.Black,
                            )
                        }
                        Surface(
                            onClick = { onExitClick() },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            Icon(
                                painterResource(id = R.drawable.ic_baseline_cancel_24),
                                contentDescription = "Flip Camera",
                                tint = Color.White,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        var showAudioDeviceDialog by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { showAudioDeviceDialog = true },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            val resource = R.drawable.volume_up_48px
                            Icon(
                                painterResource(id = resource),
                                contentDescription = "Select Audio Device",
                                tint = Color.White,
                            )
                        }
                        if (showAudioDeviceDialog) {
                            SelectAudioDeviceDialog(
                                onDismissRequest = { showAudioDeviceDialog = false },
                                selectDevice = { audioSwitchHandler?.selectDevice(it) },
                                currentDevice = audioSwitchHandler?.selectedAudioDevice,
                                availableDevices = audioSwitchHandler?.availableAudioDevices ?: emptyList()
                            )
                        }
                        Surface(
                            onClick = { viewModel.toggleSubscriptionPermissions() },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            val resource =
                                if (permissionAllowed) R.drawable.account_cancel_outline else R.drawable.account_cancel
                            Icon(
                                painterResource(id = resource),
                                contentDescription = "Permissions",
                                tint = Color.White,
                            )
                        }

                        var showDebugDialog by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { showDebugDialog = true },
                            indication = rememberRipple(false),
                            modifier = Modifier
                                .size(controlSize)
                                .padding(controlPadding)
                        ) {
                            val resource = R.drawable.dots_horizontal_circle_outline
                            Icon(
                                painterResource(id = resource),
                                contentDescription = "Permissions",
                                tint = Color.White,
                            )
                        }
                        if (showDebugDialog) {
                            DebugMenuDialog(
                                onDismissRequest = { showDebugDialog = false },
                                simulateMigration = { onSimulateMigration() },
                                fullReconnect = { fullReconnect() },
                            )
                        }
                    }
                }

                // Snack bar for errors
                val scaffoldState = rememberScaffoldState()
                val scope = rememberCoroutineScope()
                if (error != null) {
                    Scaffold(
                        scaffoldState = scaffoldState,
                        floatingActionButton = {
                            ExtendedFloatingActionButton(
                                text = { Text("Show snackbar") },
                                onClick = {
                                    // show snackbar as a suspend function
                                    scope.launch {
                                        scaffoldState.snackbarHostState.showSnackbar(error?.toString() ?: "")
                                    }
                                }
                            )
                        },
                        content = { innerPadding ->
                            Text(
                                text = "Body content",
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .fillMaxSize()
                                    .wrapContentSize()
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}