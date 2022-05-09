package io.livekit.android.videoencodedecode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState

/**
 * Widget for showing the other participant in a connection.
 */
@Composable
fun ConnectionItem(viewModel: P2PCallViewModel) {

    val track by viewModel.videoTrack.observeAsState()

    val curTrack = track
    if (curTrack != null) {
        P2PVideoItem(videoTrack = curTrack)
    }
}