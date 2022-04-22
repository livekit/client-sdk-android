package io.livekit.android.renderer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import io.livekit.android.room.track.video.ViewVisibility
import org.webrtc.SurfaceViewRenderer

class SurfaceViewRenderer : SurfaceViewRenderer, ViewVisibility.Notifier {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    override var viewVisibility: ViewVisibility? = null
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        viewVisibility?.recalculate()
    }
}