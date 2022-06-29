package io.livekit.android.dagger

import androidx.annotation.Nullable
import dagger.Module
import dagger.Provides
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import javax.inject.Named
import javax.inject.Provider

@Module
object AudioHandlerModule {
    @Provides
    fun audioHandler(
        audioSwitchHandler: Provider<AudioSwitchHandler>,
        @Named(InjectionNames.OVERRIDE_AUDIO_HANDLER)
        @Nullable
        audioHandlerOverride: AudioHandler?
    ): AudioHandler {
        return audioHandlerOverride ?: audioSwitchHandler.get()
    }
}