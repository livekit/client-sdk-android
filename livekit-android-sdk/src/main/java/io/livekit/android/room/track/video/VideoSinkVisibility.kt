package io.livekit.android.room.track.video

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.CallSuper
import androidx.compose.ui.layout.LayoutCoordinates
import io.livekit.android.room.track.Track
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
    private var lastCoordinates: LayoutCoordinates? = null

    override fun isVisible(): Boolean {
        return (lastCoordinates?.isAttached == true && lastCoordinates?.size?.width != 0 && lastCoordinates?.size?.height != 0)
    }

    override fun size(): Track.Dimensions {
        val width = lastCoordinates?.size?.width ?: 0
        val height = lastCoordinates?.size?.height ?: 0
        return Track.Dimensions(width, height)
    }

    fun onGloballyPositioned(layoutCoordinates: LayoutCoordinates) {
        val lastVisible = isVisible()
        val lastSize = size()
        lastCoordinates = layoutCoordinates

        if (lastVisible != isVisible() || lastSize != size()) {
            notifyChanged()
        }
    }

    fun onDispose() {
        if (lastCoordinates == null) {
            return
        }
        lastCoordinates = null
        notifyChanged()
    }
}

class ViewVisibility(private val view: View) : VideoSinkVisibility() {

    private val handler = Handler(Looper.getMainLooper())
    private val globalLayoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
        val lastVisibility = false
        val lastSize = Track.Dimensions(0, 0)

        override fun onGlobalLayout() {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
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
            }, 2000)
        }
    }

    init {
        view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private val loc = IntArray(2)
    private val viewRect = Rect()
    private val windowRect = Rect()

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
    }
}