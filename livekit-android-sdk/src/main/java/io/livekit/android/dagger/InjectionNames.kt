/*
 * Copyright 2023-2024 LiveKit, Inc.
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
    const val DISPATCHER_DEFAULT = "dispatcher_default"

    /**
     * @see [kotlinx.coroutines.Dispatchers.IO]
     */
    const val DISPATCHER_IO = "dispatcher_io"

    /**
     * @see [kotlinx.coroutines.Dispatchers.Main]
     */
    const val DISPATCHER_MAIN = "dispatcher_main"

    /**
     * @see [kotlinx.coroutines.Dispatchers.Unconfined]
     */
    const val DISPATCHER_UNCONFINED = "dispatcher_unconfined"

    const val SENDER = "sender"

    const val OPTIONS_VIDEO_HW_ACCEL = "options_video_hw_accel"

    const val LIB_WEBRTC_INITIALIZATION = "lib_webrtc_initialization"

    const val LOCAL_AUDIO_RECORD_SAMPLES_DISPATCHER = "local_audio_record_samples_dispatcher"
    const val LOCAL_AUDIO_BUFFER_CALLBACK_DISPATCHER = "local_audio_record_samples_dispatcher"

    // Overrides
    const val OVERRIDE_OKHTTP = "override_okhttp"
    const val OVERRIDE_AUDIO_DEVICE_MODULE = "override_audio_device_module"
    const val OVERRIDE_AUDIO_PROCESSOR_OPTIONS = "override_audio_processor_options"
    const val OVERRIDE_JAVA_AUDIO_DEVICE_MODULE_CUSTOMIZER = "override_java_audio_device_module_customizer"
    const val OVERRIDE_VIDEO_ENCODER_FACTORY = "override_video_encoder_factory"
    const val OVERRIDE_VIDEO_DECODER_FACTORY = "override_video_decoder_factory"
    const val OVERRIDE_AUDIO_HANDLER = "override_audio_handler"
    const val OVERRIDE_AUDIO_OUTPUT_TYPE = "override_audio_output_type"
    const val OVERRIDE_DISABLE_COMMUNICATION_WORKAROUND = "override_disable_communication_workaround"
    const val OVERRIDE_EGL_BASE = "override_egl_base"
    const val OVERRIDE_PEER_CONNECTION_FACTORY_OPTIONS = "override_peer_connection_factory_options"
}
