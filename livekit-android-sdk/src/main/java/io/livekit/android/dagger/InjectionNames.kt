package io.livekit.android.dagger

object InjectionNames {

    /**
     * @see [kotlinx.coroutines.Dispatchers.Default]
     */
    internal const val DISPATCHER_DEFAULT = "dispatcher_default"

    /**
     * @see [kotlinx.coroutines.Dispatchers.IO]
     */
    internal const val DISPATCHER_IO = "dispatcher_io";

    /**
     * @see [kotlinx.coroutines.Dispatchers.Main]
     */
    internal const val DISPATCHER_MAIN = "dispatcher_main"

    /**
     * @see [kotlinx.coroutines.Dispatchers.Unconfined]
     */
    internal const val DISPATCHER_UNCONFINED = "dispatcher_unconfined"

    internal const val OPTIONS_VIDEO_HW_ACCEL = "options_video_hw_accel"

    // Overrides
    internal const val OVERRIDE_OKHTTP = "override_okhttp"
    internal const val OVERRIDE_AUDIO_DEVICE_MODULE = "override_audio_device_module"
    internal const val OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER = "override_java_audio_device_module_customizer"
    internal const val OVERRIDE_VIDEO_ENCODER_FACTORY = "override_video_encoder_factory"
    internal const val OVERRIDE_VIDEO_DECODER_FACTORY = "override_video_decoder_factory"
    internal const val OVERRIDE_AUDIO_HANDLER = "override_audio_handler"
}