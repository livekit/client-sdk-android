/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room.track

import android.view.View
import androidx.annotation.VisibleForTesting
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.events.TrackEvent
import io.livekit.android.room.track.video.VideoSinkVisibility
import io.livekit.android.room.track.video.ViewVisibility
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.VideoSink
import javax.inject.Named
import kotlin.math.max

class RemoteVideoTrack(
    name: String,
    rtcTrack: livekit.org.webrtc.VideoTrack,
    val autoManageVideo: Boolean = false,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val dispatcher: CoroutineDispatcher,
    receiver: RtpReceiver,
) : VideoTrack(name, rtcTrack) {

    private var coroutineScope = CoroutineScope(dispatcher + SupervisorJob())
    private val sinkVisibilityMap = mutableMapOf<VideoSink, VideoSinkVisibility>()
    private val visibilities
        get() = sinkVisibilityMap.values

    /**
     * @suppress
     */
    @VisibleForTesting
    var lastVisibility = false
        private set

    /**
     * @suppress
     */
    @VisibleForTesting
    var lastDimensions: Dimensions = Dimensions(0, 0)
        private set

    internal var receiver: RtpReceiver

    init {
        this.receiver = receiver
    }

    /**
     * If `autoManageVideo` is enabled, a VideoSinkVisibility should be passed, using
     * [ViewVisibility] if using a traditional View layout, or ComposeVisibility
     * if using Jetpack Compose.
     *
     * By default, any Views passed to this method will be added with a [ViewVisibility].
     */
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
            synchronized(sinkVisibilityMap) {
                sinkVisibilityMap[renderer] = visibility
            }
            visibility.addObserver { _, _ -> recalculateVisibility() }
            recalculateVisibility()
        } else {
            LKLog.w { "attempted to tracking video sink visibility on an non auto managed video track." }
        }
    }

    override fun removeRenderer(renderer: VideoSink) {
        super.removeRenderer(renderer)

        val visibility = synchronized(sinkVisibilityMap) {
            sinkVisibilityMap.remove(renderer)
        }

        visibility?.close()
        if (autoManageVideo && visibility != null) {
            recalculateVisibility()
        }
    }

    override fun stop() {
        super.stop()
        synchronized(sinkVisibilityMap) {
            sinkVisibilityMap.values.forEach { it.close() }
            sinkVisibilityMap.clear()
        }
    }

    private fun hasVisibleSinks(): Boolean {
        synchronized(sinkVisibilityMap) {
            return visibilities.any { it.isVisible() }
        }
    }

    private fun largestVideoViewSize(): Dimensions {
        var maxWidth = 0
        var maxHeight = 0

        synchronized(sinkVisibilityMap) {
            visibilities.forEach { visibility ->
                val size = visibility.size()
                maxWidth = max(maxWidth, size.width)
                maxHeight = max(maxHeight, size.height)
            }
        }
        return Dimensions(maxWidth, maxHeight)
    }

    private fun recalculateVisibility() {
        var isVisible: Boolean
        var newDimensions: Dimensions

        synchronized(sinkVisibilityMap) {
            isVisible = hasVisibleSinks()
            newDimensions = largestVideoViewSize()
        }

        val eventsToPost = mutableListOf<TrackEvent>()
        if (isVisible != lastVisibility) {
            lastVisibility = isVisible
            eventsToPost.add(TrackEvent.VisibilityChanged(this, isVisible))
        }
        if (newDimensions != lastDimensions) {
            lastDimensions = newDimensions
            eventsToPost.add(TrackEvent.VideoDimensionsChanged(this, newDimensions))
        }

        if (eventsToPost.any()) {
            coroutineScope.launch {
                eventBus.postEvents(eventsToPost)
            }
        }
    }

    override fun dispose() {
        super.dispose()
        coroutineScope.cancel()
    }
}
