package io.livekit.android

import io.livekit.android.coroutines.TestCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.mockito.junit.MockitoJUnit

@ExperimentalCoroutinesApi
abstract class BaseTest {

    @get:Rule
    var mockitoRule = MockitoJUnit.rule()

    @get:Rule
    var coroutineRule = TestCoroutineRule()

    @ExperimentalCoroutinesApi
    fun runTest(testBody: suspend TestScope.() -> Unit) = coroutineRule.scope.runTest(testBody = testBody)
}
