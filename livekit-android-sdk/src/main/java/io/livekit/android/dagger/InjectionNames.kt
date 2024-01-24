/*
 * Copyright 2023 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.dagger

/**
 * @suppress
 */
object InjectionNames {

    /**
     * @see [kotlinx.coroutines.Dispatchers.Default]
     */
    internal const val DISPATCHER_DEFAULT = "dispatcher_default"

    /**
     * @see [kotlinx.coroutines.Dispatchers.IO]
     */
    internal const val DISPATCHER_IO = "dispatcher_io"

    /**
     * @see [kotlinx.coroutines.Dispatchers.Main]
     */
    internal const val DISPATCHER_MAIN = "dispatcher_main"

    /**
     * @see [kotlinx.coroutines.Dispatchers.Unconfined]
     */
    internal const val DISPATCHER_UNCONFINED = "dispatcher_unconfined"

    internal const val SENDER = "sender"

    internal const val OPTIONS_VIDEO_HW_ACCEL = "options_video_hw_accel"

    internal const val LIB_WEBRTC_INITIALIZATION = "lib_webrtc_initialization"

    // Overrides
    internal const val OVERRIDE_OKHTTP = "override_okhttp"
    internal const val OVERRIDE_AUDIO_DEVICE_MODULE = "override_audio_device_module"
    internal const val OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER = "override_java_audio_device_module_customizer"
    internal const val OVERRIDE_VIDEO_ENCODER_FACTORY = "override_video_encoder_factory"
    internal const val OVERRIDE_VIDEO_DECODER_FACTORY = "override_video_decoder_factory"
    internal const val OVERRIDE_AUDIO_HANDLER = "override_audio_handler"
    internal const val OVERRIDE_AUDIO_OUTPUT_TYPE = "override_audio_output_type"
    internal const val OVERRIDE_DISABLE_COMMUNICATION_WORKAROUND = "override_disable_communication_workaround"
    internal const val OVERRIDE_EGL_BASE = "override_egl_base"
}
