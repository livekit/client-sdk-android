package io.livekit.android.videoencodedecode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * Widget for displaying a VideoTrack. Handles the Compose <-> AndroidView interop needed to use
 * [TextureViewRenderer].
 */
@Composable
fun P2PVideoItem(
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier
) {

    var videoView: TextureViewRenderer? by remember { mutableStateOf(null) }
    var receivedVideoFrames by remember { mutableStateOf(false) }

    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            videoView?.release()
        }
    }

    Box(
        modifier = modifier
    ) {
        AndroidView(
            factory = { context ->
                TextureViewRenderer(context).apply {
                    this.init(null, null)
                    videoTrack.addSink(this)
                    videoTrack.addSink(object : VideoSink{
                        override fun onFrame(p0: VideoFrame?) {
                            TODO("Not yet implemented")
                        }

                    })
                    this.addFrameListener({
                        if (!receivedVideoFrames) {
                            receivedVideoFrames = true
                        }
                    }, 0f)
                    videoView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
        )

        // Frame Indicator
        if (receivedVideoFrames) {
            val frameIndicatorColor = Color.Green
            Surface(
                color = frameIndicatorColor,
                modifier = Modifier
                    .size(40.dp)
                    .semantics { testTag = VIDEO_FRAME_INDICATOR }
            ) {}
        }
    }
}
