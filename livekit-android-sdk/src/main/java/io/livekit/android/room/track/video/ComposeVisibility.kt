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

package io.livekit.android.room.track.video

import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track

/**
 *  A [VideoSinkVisibility] for compose views.
 *
 *  To use, pass an `onGloballyPositioned` modifier your composable like so:
 *  ```
 *  modifier = Modifier.onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) }
 *  ```
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

    /**
     * To be called from a compose view, using `Modifier.onGloballyPositioned`.
     *
     * Example:
     * ```
     * modifier = Modifier.onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) }
     * ```
     */
    fun onGloballyPositioned(layoutCoordinates: LayoutCoordinates) {
        // Note, LayoutCoordinates are mutable and may be reused.
        coordinates = layoutCoordinates
        val visible = isVisible()
        val size = size()

        if (lastVisible != visible || lastSize != size) {
            notifyChanged()
        }

        lastVisible = visible
        lastSize = size
    }

    /**
     * To be called when the associated compose view no longer exists.
     *
     * Example:
     * ```
     * DisposableEffect(room, videoTrack) {
     *     onDispose {
     *         videoSinkVisibility.onDispose()
     *     }
     * }
     * ```
     */
    fun onDispose() {
        if (coordinates == null) {
            return
        }
        coordinates = null
        notifyChanged()
    }
}
