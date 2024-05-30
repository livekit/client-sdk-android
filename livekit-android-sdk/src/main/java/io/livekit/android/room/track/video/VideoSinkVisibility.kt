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

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.CallSuper
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.ViewVisibility.Notifier
import java.util.Observable

/**
 * Provides the visibility and dimensions of the video sink, allowing LiveKit
 * to automatically manage the quality of the stream when adaptive streaming
 * is used.
 */
abstract class VideoSinkVisibility : Observable() {
    /**
     * @return whether this VideoSink is visible or not.
     */
    abstract fun isVisible(): Boolean

    /**
     * @return the dimensions of this VideoSink.
     */
    abstract fun size(): Track.Dimensions

    /**
     * This should be called whenever the visibility or size has changed.
     */
    fun notifyChanged() {
        setChanged()
        notifyObservers()
    }

    /**
     * Called when this object is no longer needed and should clean up any unused resources.
     */
    @CallSuper
    open fun close() {
        deleteObservers()
    }
}

/**
 * A [VideoSinkVisibility] for views. If using a custom view other than the sdk provided renderers,
 * you must implement [Notifier], override [View.onVisibilityChanged] and call through to [recalculate], or
 * the visibility may not be calculated correctly.
 */
class ViewVisibility(private val view: View) : VideoSinkVisibility() {

    private val lastVisibility = false
    private val lastSize = Track.Dimensions(0, 0)

    private val handler = Handler(Looper.getMainLooper())
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleRecalculate()
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        scheduleRecalculate()
    }

    /**
     * Declares that this [View] will override [View.onVisibilityChanged] and call through to [recalculate],
     * partaking in the [VideoSinkVisibility] system.
     */
    interface Notifier {
        var viewVisibility: ViewVisibility?
    }

    init {
        view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        view.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        if (view is Notifier) {
            view.viewVisibility = this
        }
    }

    private val loc = IntArray(2)
    private val viewRect = Rect()
    private val windowRect = Rect()

    private fun scheduleRecalculate() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(
            {
                recalculate()
            },
            2000,
        )
    }

    /**
     * Recalculates whether this view's visibility has changed, and notifies observers if it has.
     */
    fun recalculate() {
        var shouldNotify = false
        val newVisibility = isVisible()
        val newSize = size()
        if (newVisibility != lastVisibility) {
            shouldNotify = true
        }
        if (newSize != lastSize) {
            shouldNotify = true
        }

        if (shouldNotify) {
            notifyChanged()
        }
    }

    private fun isViewAncestorsVisible(view: View): Boolean {
        if (view.visibility != View.VISIBLE) {
            return false
        }
        val parent = view.parent as? View
        if (parent != null) {
            return isViewAncestorsVisible(parent)
        }
        return true
    }

    override fun isVisible(): Boolean {
        if (view.windowVisibility != View.VISIBLE || !isViewAncestorsVisible(view)) {
            return false
        }

        view.getLocationInWindow(loc)
        viewRect.set(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)

        view.getWindowVisibleDisplayFrame(windowRect)
        // Ensure window rect origin is at 0,0
        windowRect.offset(-windowRect.left, -windowRect.top)

        return viewRect.intersect(windowRect)
    }

    override fun size(): Track.Dimensions {
        return Track.Dimensions(view.width, view.height)
    }

    override fun close() {
        super.close()
        handler.removeCallbacksAndMessages(null)
        view.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        view.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        if (view is Notifier && view.viewVisibility == this) {
            view.viewVisibility = null
        }
    }
}
