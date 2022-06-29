package io.livekit.android.mock.dagger

import dagger.Binds
import dagger.Module
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.NoAudioHandler

@Module
interface TestAudioHandlerModule {
    @Binds
    fun audioHandler(audioHandler: NoAudioHandler): AudioHandler
}