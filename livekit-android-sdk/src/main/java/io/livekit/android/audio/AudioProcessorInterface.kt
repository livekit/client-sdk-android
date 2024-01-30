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

package io.livekit.android.audio

import java.nio.ByteBuffer

/**
 * Interface for external audio processing.
 */
interface AudioProcessorInterface {
    /**
     * Check if the audio processing is enabled.
     */
    fun isEnabled(url: String, token: String): Boolean

    /**
     * Get the name of the audio processing.
     */
    fun getName(): String

    /**
     * Initialize the audio processing.
     */
    fun initialize(sampleRateHz: Int, numChannels: Int)

    /**
     * Called when the sample rate has changed.
     */
    fun reset(newRate: Int)

    /**
     * Process the audio frame (10ms).
     */
    fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer)
}
