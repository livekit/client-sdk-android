package io.livekit.android.room.track.video

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.CallSuper
import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.video.ViewVisibility.Notifier
import java.util.*

abstract class VideoSinkVisibility : Observable() {
    abstract fun isVisible(): Boolean
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
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            recalculate()
        }, 2000)
    }

    interface Notifier {
        var viewVisibility: ViewVisibility?
    }

    init {
        view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        if (view is Notifier) {
            view.viewVisibility = this
        }
    }

    private val loc = IntArray(2)
    private val viewRect = Rect()
    private val windowRect = Rect()

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
        view.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        if (view is Notifier && view.viewVisibility == this) {
            view.viewVisibility = null
        }
    }
}