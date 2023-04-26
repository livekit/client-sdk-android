package io.livekit.android.room.track.video

import org.webrtc.CameraVideoCapturer.CameraEventsHandler

/**
 * Dispatches CameraEventsHandler callbacks to registered handlers.
 *
 * @suppress
 */
internal class CameraEventsDispatchHandler : CameraEventsHandler {
    private val handlers = mutableSetOf<CameraEventsHandler>()

    @Synchronized
    fun registerHandler(handler: CameraEventsHandler) {
        handlers.add(handler)
    }

    @Synchronized
    fun unregisterHandler(handler: CameraEventsHandler) {
        handlers.remove(handler)
    }

    override fun onCameraError(errorDescription: String) {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onCameraError(errorDescription)
        }
    }

    override fun onCameraDisconnected() {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onCameraDisconnected()
        }
    }

    override fun onCameraFreezed(errorDescription: String) {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onCameraFreezed(errorDescription)
        }
    }

    override fun onCameraOpening(cameraName: String) {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onCameraOpening(cameraName)
        }
    }

    override fun onFirstFrameAvailable() {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onFirstFrameAvailable()
        }
    }

    override fun onCameraClosed() {
        val handlersCopy = handlers.toMutableSet()
        for (handler in handlersCopy) {
            handler.onCameraClosed()
        }
    }

}