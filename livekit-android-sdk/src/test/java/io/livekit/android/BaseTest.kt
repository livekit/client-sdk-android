package io.livekit.android

import io.livekit.android.coroutines.TestCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseTest {
    // Uncomment to enable logging in tests.
    //@get:Rule
    //var loggingRule = LoggingRule()

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun runTest(testBody: suspend TestScope.() -> Unit) = coroutineRule.scope.runTest(testBody = testBody)
}
