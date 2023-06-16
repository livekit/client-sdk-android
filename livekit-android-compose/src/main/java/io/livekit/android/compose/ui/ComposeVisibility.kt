package io.livekit.android.compose.ui

import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.VideoSinkVisibility

/**
 *
 */
class ComposeVisibility : VideoSinkVisibility() {
    private var coordinates: LayoutCoordinates? = null

    private var lastVisible = isVisible()
    private var lastSize = size()
    override fun isVisible(): Boolean {
        return (coordinates?.isAttached == true &&
                coordinates?.size?.width != 0 &&
                coordinates?.size?.height != 0)
    }

    override fun size(): Track.Dimensions {
        val width = coordinates?.size?.width ?: 0
        val height = coordinates?.size?.height ?: 0
        return Track.Dimensions(width, height)
    }

    // Note, LayoutCoordinates are mutable and may be reused.
    fun onGloballyPositioned(layoutCoordinates: LayoutCoordinates) {
        coordinates = layoutCoordinates
        val visible = isVisible()
        val size = size()

        if (lastVisible != visible || lastSize != size) {
            notifyChanged()
        }

        lastVisible = visible
        lastSize = size
    }

    fun onDispose() {
        if (coordinates == null) {
            return
        }
        coordinates = null
        notifyChanged()
    }
}