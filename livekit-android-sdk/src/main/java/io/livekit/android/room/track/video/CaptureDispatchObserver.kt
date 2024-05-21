package io.livekit.android.room.track.video

import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

class CaptureDispatchObserver : CapturerObserver {
    private val observers = linkedSetOf<CapturerObserver>()
    private val sinks = linkedSetOf<VideoSink>()

    @Synchronized
    fun registerObserver(observer: CapturerObserver) {
        observers.add(observer)
    }

    @Synchronized
    fun unregisterObserver(observer: CapturerObserver) {
        observers.remove(observer)
    }

    @Synchronized
    fun registerSink(sink: VideoSink) {
        sinks.add(sink)
    }

    @Synchronized
    fun unregisterSink(sink: VideoSink) {
        sinks.remove(sink)
    }

    @Synchronized
    override fun onCapturerStarted(success: Boolean) {
        for (observer in observers) {
            observer.onCapturerStarted(success)
        }
    }

    @Synchronized
    override fun onCapturerStopped() {
        for (observer in observers) {
            observer.onCapturerStopped()
        }
    }

    @Synchronized
    override fun onFrameCaptured(frame: VideoFrame) {
        for (observer in observers) {
            observer.onFrameCaptured(frame)
        }

        for (sink in sinks) {
            sink.onFrame(frame)
        }
    }
}
