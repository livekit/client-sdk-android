package io.livekit.android.audio

import livekit.org.webrtc.audio.JavaAudioDeviceModule

/**
 * @suppress
 */
interface AudioRecordPrewarmer {
    fun prewarm()
    fun stop()
}

/**
 * @suppress
 */
class NoAudioRecordPrewarmer : AudioRecordPrewarmer {
    override fun prewarm() {
        // nothing to do.
    }

    override fun stop() {
        // nothing to do.
    }
}

/**
 * @suppress
 */
class JavaAudioRecordPrewarmer(private val audioDeviceModule: JavaAudioDeviceModule) : AudioRecordPrewarmer {
    override fun prewarm() {
        audioDeviceModule.prewarmRecording()
    }

    override fun stop() {
        audioDeviceModule.requestStopRecording()
    }

}
