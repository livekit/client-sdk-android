@file:Suppress("UnstableApiUsage", "NewObjectEquality")

package io.livekit.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class FlowDelegateUsageDetectorTest {
    @Test
    fun normalFlowAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class Example {
                        @FlowObservable
                        @get:FlowObservable
                        val value: Int by flowDelegate(0)
                        fun foo() {
                            ::value.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun thisColonAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class Example {
                        @FlowObservable
                        @get:FlowObservable
                        val value: Int by flowDelegate(0)
                        fun foo() {
                            this::value.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun otherClassAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class FlowContainer {
                        @FlowObservable
                        @get:FlowObservable
                        val value: Int by flowDelegate(0)
                    }
                    class Example {
                        fun foo() {
                            val container = FlowContainer()
                            container::value.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun parenthesesClassAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class FlowContainer {
                        @FlowObservable
                        @get:FlowObservable
                        val value: Int by flowDelegate(0)
                    }
                    class Example {
                        fun foo() {
                            val container = FlowContainer()
                            (container)::value.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun roundaboutAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class FlowContainer {
                        var value: Int by flowDelegate(0)
                        @FlowObservable
                        @get:FlowObservable
                        val otherValue: Int
                            get() = value
                    }
                    class Example {
                        fun foo() {
                            val container = FlowContainer()
                            container::otherValue.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun nonAnnotatedFlowAccess() {
        lint()
            .allowMissingSdk()
            .files(
                flowAccess(),
                stateFlow(),
                kotlin(
                    """
                    package foo
                    import io.livekit.android.util.FlowObservable
                    import io.livekit.android.util.flow
                    import io.livekit.android.util.flowDelegate
                    class Example {
                        val value: Int = 0
                        fun foo() {
                            this::value.flow
                            return
                        }
                    }"""
                ).indented()
            )
            .issues(FlowDelegateUsageDetector.ISSUE)
            .run()
            .expectErrorCount(1)
    }
}

fun flowAccess(): TestFile {
    return kotlin(
        "io/livekit/android/util/FlowDelegate.kt",
        """
        package io.livekit.android.util
        
        import kotlin.reflect.KProperty
        import kotlin.reflect.KProperty0
        import kotlinx.coroutines.flow.StateFlow
        import kotlinx.coroutines.flow.MutableStateFlow

        internal val <T> KProperty0<T>.delegate: Any?
            get() { getDelegate() }
        
        @Suppress("UNCHECKED_CAST")
        val <T> KProperty0<T>.flow: StateFlow<T>
            get() = delegate as StateFlow<T>
        
        @Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
        @Retention(AnnotationRetention.SOURCE)
        @MustBeDocumented
        annotation class FlowObservable
        
        @FlowObservable
        class MutableStateFlowDelegate<T>
        internal constructor(
            private val flow: MutableStateFlow<T>,
            private val onSetValue: ((newValue: T, oldValue: T) -> Unit)? = null
        ) : MutableStateFlow<T> by flow {
        
            operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
                return flow.value
            }
        
            operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                val oldValue = flow.value
                flow.value = value
                onSetValue?.invoke(value, oldValue)
            }
        }
        
        public fun <T> flowDelegate(
            initialValue: T,
            onSetValue: ((newValue: T, oldValue: T) -> Unit)? = null
        ): MutableStateFlowDelegate<T> {
            return MutableStateFlowDelegate(MutableStateFlow(initialValue), onSetValue)
        }
    """
    ).indented()
        .within("src")
}

fun stateFlow(): TestFile {
    return kotlin(
        """
        package kotlinx.coroutines.flow
        interface StateFlow<out T> {
            val value: T
        }
        class MutableStateFlow<T>(override var value: T) : StateFlow<T>
    """
    ).indented()
}
