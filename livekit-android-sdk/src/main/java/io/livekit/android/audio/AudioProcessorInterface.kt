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
    fun isEnabled(): Boolean

    /**
     * Get the name of the audio processing.
     */
    fun getName(): String

    /**
     * Initialize the audio processing.
     *
     * Note: audio processing methods will be called regardless of whether
     * [isEnabled] returns true or not.
     */
    fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int)

    /**
     * Called when the sample rate has changed.
     *
     * Note: audio processing methods will be called regardless of whether
     * [isEnabled] returns true or not.
     */
    fun resetAudioProcessing(newRate: Int)

    /**
     * Process the audio frame (10ms).
     *
     * Note: audio processing methods will be called regardless of whether
     * [isEnabled] returns true or not.
     */
    fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer)
}

/**
 * @suppress
 */
interface AuthedAudioProcessorInterface : AudioProcessorInterface {
    /**
     * @suppress
     */
    fun authenticate(url: String, token: String)
}
