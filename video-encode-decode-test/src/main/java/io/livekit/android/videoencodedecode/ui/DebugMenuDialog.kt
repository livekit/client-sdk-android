package io.livekit.android.composesample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

@Preview
@Composable
fun DebugMenuDialog(
    onDismissRequest: () -> Unit = {},
    simulateMigration: () -> Unit = {},
    fullReconnect: () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.DarkGray, shape = RoundedCornerShape(3.dp))
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Text("Debug Menu", color = Color.White)
            Spacer(modifier = Modifier.height(10.dp))

            Button(onClick = {
                simulateMigration()
                onDismissRequest()
            }) {
                Text("Simulate Migration")
            }
            Button(onClick = {
                fullReconnect()
                onDismissRequest()
            }) {
                Text("Reconnect to room")
            }
        }
    }
}
