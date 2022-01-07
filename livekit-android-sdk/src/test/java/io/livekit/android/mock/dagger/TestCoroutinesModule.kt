package io.livekit.android.mock.dagger

import dagger.Module
import dagger.Provides
import io.livekit.android.dagger.InjectionNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import javax.inject.Named

@Module
class TestCoroutinesModule(
    @OptIn(ExperimentalCoroutinesApi::class)
    val coroutineDispatcher: CoroutineDispatcher = TestCoroutineDispatcher()
) {

    @Provides
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    fun defaultDispatcher() = coroutineDispatcher

    @Provides
    @Named(InjectionNames.DISPATCHER_IO)
    fun ioDispatcher() = coroutineDispatcher

    @Provides
    @Named(InjectionNames.DISPATCHER_MAIN)
    fun mainDispatcher() = coroutineDispatcher

    @Provides
    @Named(InjectionNames.DISPATCHER_UNCONFINED)
    fun unconfinedDispatcher() = coroutineDispatcher
}