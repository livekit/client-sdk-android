/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room

import livekit.org.webrtc.PeerConnection.SignalingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsDuplicateAnswerTest {

    @Test
    fun `drops an answer while the same offer is still being answered`() {
        assertTrue(isDuplicateAnswer(3, 3, 0, SignalingState.HAVE_LOCAL_OFFER))
    }

    @Test
    fun `drops a repeat of an unnumbered answer while the first is still applying`() {
        assertTrue(isDuplicateAnswer(0, 0, 0, SignalingState.HAVE_LOCAL_OFFER))
    }

    @Test
    fun `drops an answer for an offer whose answer was already applied`() {
        assertTrue(isDuplicateAnswer(3, null, 3, SignalingState.STABLE))
    }

    @Test
    fun `accepts a retry after a failed apply`() {
        // Nothing in flight and nothing applied for this offer, so the answer is still wanted.
        assertFalse(isDuplicateAnswer(3, null, 2, SignalingState.HAVE_LOCAL_OFFER))
    }

    @Test
    fun `accepts the answer for a new offer`() {
        assertFalse(isDuplicateAnswer(4, null, 3, SignalingState.HAVE_LOCAL_OFFER))
    }

    @Test
    fun `without an offerId drops an answer in stable`() {
        assertTrue(isDuplicateAnswer(0, null, 0, SignalingState.STABLE))
    }

    @Test
    fun `without an offerId accepts an answer while a local offer is pending`() {
        assertFalse(isDuplicateAnswer(0, null, 0, SignalingState.HAVE_LOCAL_OFFER))
    }

    @Test
    fun `an answer in flight for a different offer does not mask a new one`() {
        assertFalse(isDuplicateAnswer(4, 3, 0, SignalingState.HAVE_LOCAL_OFFER))
    }
}
