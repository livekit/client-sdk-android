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

package io.livekit.android.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import io.livekit.android.room.track.video.ViewVisibility
import io.livekit.android.util.LKLog
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame

open class SurfaceViewRenderer : SurfaceViewRenderer, ViewVisibility.Notifier {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    override var viewVisibility: ViewVisibility? = null
    private var initialized = false

    override fun init(sharedContext: EglBase.Context?, rendererEvents: RendererCommon.RendererEvents?, configAttributes: IntArray?, drawer: RendererCommon.GlDrawer?) {
        if (initialized) {
            LKLog.w { "Reinitializing already initialized SurfaceViewRenderer." }
        }
        initialized = true
        super.init(sharedContext, rendererEvents, configAttributes, drawer)
    }

    override fun release() {
        initialized = false
        super.release()
    }

    @SuppressLint("LogNotTimber")
    override fun onFrame(frame: VideoFrame) {
        if (!initialized) {
            Log.e("SurfaceViewRenderer", "Received frame when not initialized! You must call Room.initVideoRenderer(view) before using this view!")
        }
        super.onFrame(frame)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        viewVisibility?.recalculate()
    }
}
