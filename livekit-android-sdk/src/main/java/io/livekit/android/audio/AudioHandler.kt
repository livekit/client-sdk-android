package io.livekit.android.audio

/**
 * Interface for handling android audio routing.
 */
interface AudioHandler {
    /**
     * Called when a room is started.
     */
    fun start()

    /**
     * Called when a room is disconnected.
     */
    fun stop()
}