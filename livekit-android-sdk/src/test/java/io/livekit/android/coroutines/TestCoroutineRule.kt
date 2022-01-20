package io.livekit.android.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@OptIn(ExperimentalCoroutinesApi::class)
class TestCoroutineRule : TestRule {
    val dispatcher = UnconfinedTestDispatcher()
    val scope = TestScope(dispatcher)

    override fun apply(base: Statement, description: Description?) = object : Statement() {
        @Throws(Throwable::class)
        override fun evaluate() {
            Dispatchers.setMain(dispatcher)
            base.evaluate()
            val timeAfterTest = dispatcher.scheduler.currentTime
            dispatcher.scheduler.advanceUntilIdle() // run the remaining tasks
            Assert.assertEquals(
                timeAfterTest,
                dispatcher.scheduler.currentTime
            ) // will fail if there were tasks scheduled at a later moment
            Dispatchers.resetMain()
        }
    }
}