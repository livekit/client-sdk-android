package io.livekit.android.room.track.video

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * Delivers capturer observer callbacks to multiple observers.
 *
 * Observers that aren't WebRTC should avoid blocking the thread with long running operations.
 */
class CapturerObserverDelegate(private val observers: Iterable<CapturerObserver>) : CapturerObserver {

    /** Notify if the capturer have been started successfully or not.  */
    override fun onCapturerStarted(success: Boolean) {
        observers.forEach { it.onCapturerStarted(success) }
    }

    /** Notify that the capturer has been stopped.  */
    override fun onCapturerStopped() {
        observers.forEach { it.onCapturerStopped() }
    }

    /** Delivers a captured frame.  */
    override fun onFrameCaptured(frame: VideoFrame?) {
        observers.forEach { it.onFrameCaptured(frame) }
    }
}