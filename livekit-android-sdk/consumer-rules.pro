# Kotlin Serialization Proguard Rules
########################################

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.livekit.android.**$$serializer { *; }

-keepclasseswithmembers class io.livekit.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebRTC
#########################################
# Ensure java methods called from Native are preserved.
-keepclasseswithmembers,includedescriptorclasses class * {
    @livekit.org.webrtc.CalledByNative <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @livekit.org.webrtc.CalledByNativeUnchecked <methods>;
}

# NIST sdp parser
#########################################
# Preserve reflection used for Parser registrations
-keep class android.gov.nist.javax.sdp.parser.*Parser { *; }
-keep class android.gov.nist.javax.sdp.parser.ParserFactory { *; }
-keep class android.gov.nist.javax.sdp.parser.SDPParser { *; }

# Protobuf
#########################################
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
