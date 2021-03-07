package io.livekit.android.dagger

import com.google.protobuf.util.JsonFormat
import dagger.Module
import dagger.Provides

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
    }
}