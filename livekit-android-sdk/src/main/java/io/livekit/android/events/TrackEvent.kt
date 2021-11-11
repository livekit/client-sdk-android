package io.livekit.android.events

import io.livekit.android.room.track.Track

sealed class TrackEvent : Event() {
    class VisibilityChanged(val isVisible: Boolean) : TrackEvent()
    class VideoDimensionsChanged(val newDimensions: Track.Dimensions) : TrackEvent()
}