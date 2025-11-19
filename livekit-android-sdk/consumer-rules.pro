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
-keepclassmembers class io.livekit.android.** {
    *** Companion;
}
-keepclasseswithmembers class io.livekit.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WebRTC
#########################################
-keep class livekit.org.webrtc.** { *; }

# JNI Zero initialization (required for WebRTC native method registration)
-keep class livekit.org.jni_zero.JniInit {
    # Keep the init method un-obfuscated for native code callback
    private static java.lang.Object[] init();
}

# NIST sdp parser
#########################################
-keep class android.gov.nist.** { *; }
-dontwarn com.sun.nio.sctp.**
-dontwarn org.apache.log4j.**

# Protobuf
#########################################
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Klaxon JSON parsing
#########################################
# Klaxon uses reflection and doesn't ship ProGuard rules.
# Keep Klaxon library classes for reflection to work
-keep class com.beust.klaxon.** { *; }
-keep interface com.beust.klaxon.** { *; }
# Data classes using Klaxon should be annotated with @Keep at the source level
