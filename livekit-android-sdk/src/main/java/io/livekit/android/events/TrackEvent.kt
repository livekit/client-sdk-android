package io.livekit.android.events

import io.livekit.android.room.track.Track

sealed class TrackEvent(val track: Track) : Event() {
    class VisibilityChanged(track: Track, val isVisible: Boolean) : TrackEvent(track)
    class VideoDimensionsChanged(track: Track, val newDimensions: Track.Dimensions) : TrackEvent(track)
    class StreamStateChanged(track: Track, val streamState: Track.StreamState) : TrackEvent(track)
}