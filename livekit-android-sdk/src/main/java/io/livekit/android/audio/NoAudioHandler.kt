package io.livekit.android.audio

import javax.inject.Inject

/**
 * A dummy implementation that does no audio handling.
 */
class NoAudioHandler
@Inject
constructor() : AudioHandler {
    override fun start() {
    }

    override fun stop() {
    }
}