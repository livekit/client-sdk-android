package io.livekit.android.compose.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator

data class CameraState(val selectedDeviceId: String, val deviceIds: List<String>)

@Composable
fun rememberCameraState(): MutableState<CameraState> {
    val cameraState = remember {
        mutableStateOf(
            CameraState(
                "",
                emptyList()
            )
        )
    }

    val context = LocalContext.current
    LaunchedEffect(context) {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val deviceIds = enumerator.deviceNames
        cameraState.value = CameraState(
            deviceIds.first(),
            deviceIds.toList(),
        )
    }

    return cameraState
}

@ExperimentalMaterial3Api
@Composable
fun CameraDropdownMenuBox(
    cameraState: MutableState<CameraState>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = !expanded
        },
        modifier = modifier,
    ) {

        TextField(
            // The `menuAnchor` modifier must be passed to the text field for correctness.
            modifier = Modifier.menuAnchor(),
            readOnly = true,
            value = cameraState.value.selectedDeviceId,
            onValueChange = {},
            label = { Text("Camera") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            cameraState.value.deviceIds.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption) },
                    onClick = {
                        cameraState.value = cameraState.value.copy(selectedDeviceId = selectionOption)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
