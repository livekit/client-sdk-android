package io.livekit.android.room.track

import io.livekit.android.BaseTest
import io.livekit.android.events.EventCollector
import io.livekit.android.events.TrackEvent
import io.livekit.android.mock.MockVideoStreamTrack
import io.livekit.android.room.track.video.VideoSinkVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteVideoTrackTest : BaseTest() {

    lateinit var track: RemoteVideoTrack

    @Before
    fun setup() {
        track = RemoteVideoTrack(
            name = "track",
            rtcTrack = MockVideoStreamTrack(),
            autoManageVideo = true,
            dispatcher = coroutineRule.dispatcher
        )
    }

    @Test
    fun defaultVisibility() = runTest {
        Assert.assertFalse(track.lastVisibility)
        Assert.assertEquals(Track.Dimensions(0, 0), track.lastDimensions)
    }

    @Test
    fun addVisibility() = runTest {
        val visible = true
        val size = Track.Dimensions(100, 100)
        val visibility = CustomVisibility(visible = visible, size = size)
        val eventCollector = EventCollector(track.events, coroutineRule.scope)
        track.addRenderer(EmptyVideoSink(), visibility)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        val visibilityEvent = events.first { it is TrackEvent.VisibilityChanged } as TrackEvent.VisibilityChanged
        val sizeEvent = events.first { it is TrackEvent.VideoDimensionsChanged } as TrackEvent.VideoDimensionsChanged

        Assert.assertEquals(visible, visibilityEvent.isVisible)
        Assert.assertEquals(size, sizeEvent.newDimensions)
    }

    @Test
    fun removeVisibility() = runTest {
        val sink = EmptyVideoSink()
        val visibility = CustomVisibility(visible = true, size = Track.Dimensions(100, 100))
        track.addRenderer(sink, visibility)
        val eventCollector = EventCollector(track.events, coroutineRule.scope)
        track.removeRenderer(sink)
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        val visibilityEvent = events.first { it is TrackEvent.VisibilityChanged } as TrackEvent.VisibilityChanged
        val sizeEvent = events.first { it is TrackEvent.VideoDimensionsChanged } as TrackEvent.VideoDimensionsChanged

        Assert.assertEquals(false, visibilityEvent.isVisible)
        Assert.assertEquals(Track.Dimensions(0, 0), sizeEvent.newDimensions)
    }

    @Test
    fun changeVisibility() = runTest {
        val visibility = CustomVisibility(visible = true, size = Track.Dimensions(100, 100))
        track.addRenderer(EmptyVideoSink(), visibility)

        val visible = false
        val size = Track.Dimensions(200, 200)

        val eventCollector = EventCollector(track.events, coroutineRule.scope)
        visibility.visible = visible
        visibility.size = size
        val events = eventCollector.stopCollecting()

        Assert.assertEquals(2, events.size)
        val visibilityEvent = events.first { it is TrackEvent.VisibilityChanged } as TrackEvent.VisibilityChanged
        val sizeEvent = events.first { it is TrackEvent.VideoDimensionsChanged } as TrackEvent.VideoDimensionsChanged
        Assert.assertEquals(visible, visibilityEvent.isVisible)
        Assert.assertEquals(size, sizeEvent.newDimensions)
    }

    @Test
    fun multipleVisibility() = runTest {
        val visible = true
        val size = Track.Dimensions(100, 100)
        val hiddenVisibility = CustomVisibility(visible = false, size = Track.Dimensions(0, 0))
        val visibility = CustomVisibility(visible = visible, size = size)
        track.addRenderer(EmptyVideoSink(), hiddenVisibility)

        val eventCollector = EventCollector(track.events, coroutineRule.scope)
        track.addRenderer(EmptyVideoSink(), visibility)
        val events = eventCollector.stopCollecting()

        // Make sure we're grabbing the max of visibility and dimensions
        Assert.assertEquals(2, events.size)
        val visibilityEvent = events.first { it is TrackEvent.VisibilityChanged } as TrackEvent.VisibilityChanged
        val sizeEvent = events.first { it is TrackEvent.VideoDimensionsChanged } as TrackEvent.VideoDimensionsChanged

        Assert.assertEquals(visible, visibilityEvent.isVisible)
        Assert.assertEquals(size, sizeEvent.newDimensions)
    }
}

private class EmptyVideoSink : VideoSink {
    override fun onFrame(p0: VideoFrame?) {
    }
}

private class CustomVisibility(
    visible: Boolean = false,
    size: Track.Dimensions = Track.Dimensions(0, 0)
) : VideoSinkVisibility() {

    var visible = visible
        set(value) {
            field = value
            notifyChanged()
        }

    var size = size
        set(value) {
            field = value
            notifyChanged()
        }

    override fun isVisible() = visible

    override fun size() = size

}