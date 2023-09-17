package io.livekit.android.composesample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.twilio.audioswitch.AudioDevice

@Preview
@Composable
fun SelectAudioDeviceDialog(
    onDismissRequest: () -> Unit = {},
    selectDevice: (AudioDevice) -> Unit = {},
    currentDevice: AudioDevice? = null,
    availableDevices: List<AudioDevice> = emptyList()
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.DarkGray, shape = RoundedCornerShape(3.dp))
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Text("Select Audio Device", color = Color.White)
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn {
                items(availableDevices) { device ->
                    Button(
                        onClick = {
                            selectDevice(device)
                            onDismissRequest()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(device.name)
                    }
                }
            }
        }
    }
}
