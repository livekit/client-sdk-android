package io.livekit.android.room.track

import android.view.View
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.TrackEvent
import io.livekit.android.room.track.video.VideoSinkVisibility
import io.livekit.android.room.track.video.ViewVisibility
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.VideoSink
import javax.inject.Named
import kotlin.math.max

class RemoteVideoTrack(
    name: String,
    rtcTrack: org.webrtc.VideoTrack,
    val autoManageVideo: Boolean = false,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val dispatcher: CoroutineDispatcher,
) : VideoTrack(name, rtcTrack) {

    private var coroutineScope = CoroutineScope(dispatcher + SupervisorJob())
    private val sinkVisibilityMap = mutableMapOf<VideoSink, VideoSinkVisibility>()
    private val visibilities = sinkVisibilityMap.values

    private var lastVisibility = false
    private var lastDimensions: Dimensions = Dimensions(0, 0)

    override fun addRenderer(renderer: VideoSink) {
        if (autoManageVideo && renderer is View) {
            addRenderer(renderer, ViewVisibility(renderer))
        } else {
            super.addRenderer(renderer)
        }
    }

    fun addRenderer(renderer: VideoSink, visibility: VideoSinkVisibility) {
        super.addRenderer(renderer)
        if (autoManageVideo) {
            sinkVisibilityMap[renderer] = visibility
            visibility.addObserver { _, _ -> recalculateVisibility() }
            recalculateVisibility()
        } else {
            LKLog.w { "attempted to tracking video sink visibility on an non auto managed video track." }
        }
    }

    override fun removeRenderer(renderer: VideoSink) {
        super.removeRenderer(renderer)
        val visibility = sinkVisibilityMap.remove(renderer)
        visibility?.close()
    }

    override fun stop() {
        super.stop()
        sinkVisibilityMap.values.forEach { it.close() }
        sinkVisibilityMap.clear()
    }

    private fun hasVisibleSinks(): Boolean {
        return visibilities.any { it.isVisible() }
    }

    private fun largestVideoViewSize(): Dimensions {
        var maxWidth = 0
        var maxHeight = 0
        visibilities.forEach { visibility ->
            val size = visibility.size()
            maxWidth = max(maxWidth, size.width)
            maxHeight = max(maxHeight, size.height)
        }

        return Dimensions(maxWidth, maxHeight)
    }

    private fun recalculateVisibility() {
        val isVisible = hasVisibleSinks()
        val newDimensions = largestVideoViewSize()

        val eventsToPost = mutableListOf<TrackEvent>()
        if (isVisible != lastVisibility) {
            lastVisibility = isVisible
            eventsToPost.add(TrackEvent.VisibilityChanged(isVisible))
        }
        if (newDimensions != lastDimensions) {
            lastDimensions = newDimensions
            eventsToPost.add(TrackEvent.VideoDimensionsChanged(newDimensions))
        }

        if (eventsToPost.any()) {
            coroutineScope.launch {
                eventBus.postEvents(eventsToPost)
            }
        }
    }
}