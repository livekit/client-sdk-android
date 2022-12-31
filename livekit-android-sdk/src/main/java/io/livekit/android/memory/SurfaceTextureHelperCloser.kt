package io.livekit.android.memory

import org.webrtc.SurfaceTextureHelper
import java.io.Closeable

internal class SurfaceTextureHelperCloser(private val surfaceTextureHelper: SurfaceTextureHelper) : Closeable {
    private var isClosed = false
    override fun close() {
        if (!isClosed) {
            isClosed = true
            surfaceTextureHelper.stopListening()
            surfaceTextureHelper.dispose()
        }
    }
}