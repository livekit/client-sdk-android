package io.livekit.android.dagger

import com.google.protobuf.util.JsonFormat
import dagger.Module
import dagger.Provides
import javax.inject.Named

@Module
class JsonFormatModule {
    companion object {
        @Provides
        fun jsonFormatParser(): JsonFormat.Parser {
            return JsonFormat.parser()
        }

        @Provides
        fun jsonFormatPrinter(): JsonFormat.Printer {
            return JsonFormat.printer()
        }

        @Provides
        @Named(InjectionNames.SIGNAL_JSON_ENABLED)
        fun signalJsonEnabled(): Boolean = false
    }
}