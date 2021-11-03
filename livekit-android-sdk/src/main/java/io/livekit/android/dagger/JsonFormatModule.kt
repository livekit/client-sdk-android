package io.livekit.android.dagger

import com.google.protobuf.util.JsonFormat
import dagger.Module
import dagger.Provides
import dagger.Reusable
import kotlinx.serialization.json.Json
import javax.inject.Named

@Module
object JsonFormatModule {
    @Provides
    fun protobufJsonFormatParser(): JsonFormat.Parser {
        return JsonFormat.parser()
    }

    @Provides
    fun protobufJsonFormatPrinter(): JsonFormat.Printer {
        return JsonFormat.printer()
    }

    @Provides
    @Reusable
    fun kotlinSerializationJson(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    @Provides
    @Named(InjectionNames.SIGNAL_JSON_ENABLED)
    fun signalJsonEnabled(): Boolean = false
}