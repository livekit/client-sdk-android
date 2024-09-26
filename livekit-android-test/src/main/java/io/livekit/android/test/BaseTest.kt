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

package io.livekit.android.test

import com.google.common.util.concurrent.MoreExecutors
import io.livekit.android.test.coroutines.TestCoroutineRule
import io.livekit.android.test.util.LoggingRule
import io.livekit.android.webrtc.peerconnection.overrideExecutorAndDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseTest {
    // Uncomment to enable logging in tests.
    @get:Rule
    var loggingRule = LoggingRule()

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    @get:Rule
    var globalTimeout: Timeout = Timeout.seconds(60)

    @Before
    fun setupRTCThread() {
        overrideExecutorAndDispatcher(
            executorService = MoreExecutors.newDirectExecutorService(),
            dispatcher = coroutineRule.dispatcher,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runTest(testBody: suspend TestScope.() -> Unit) = coroutineRule.scope.runTest(testBody = testBody)
}
