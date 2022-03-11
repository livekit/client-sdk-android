package io.livekit.android.dagger

import dagger.Module
import dagger.Provides
import dagger.Reusable
import kotlinx.serialization.json.Json

@Module
object JsonFormatModule {
    @Provides
    @Reusable
    fun kotlinSerializationJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

}