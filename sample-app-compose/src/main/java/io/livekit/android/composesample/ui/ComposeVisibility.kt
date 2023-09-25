/*
 * Copyright 2023 LiveKit, Inc.
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

package io.livekit.android.composesample.ui

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
