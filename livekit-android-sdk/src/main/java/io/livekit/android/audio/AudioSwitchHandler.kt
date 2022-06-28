package io.livekit.android.audio

import android.content.Context
import com.twilio.audioswitch.AudioSwitch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioSwitchHandler
@Inject
constructor(context: Context) : AudioHandler {
    private val audioSwitch = AudioSwitch(context)
    override fun start() {
        audioSwitch.start { _, _ -> }
        audioSwitch.activate()
    }

    override fun stop() {
        audioSwitch.stop()
    }
}