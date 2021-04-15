package io.livekit.android.dagger

import dagger.Module
import dagger.Provides
import kotlinx.coroutines.Dispatchers
import javax.inject.Named

@Module
object CoroutinesModule {
    @Provides
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    fun defaultDispatcher() = Dispatchers.Default

    @Provides
    @Named(InjectionNames.DISPATCHER_IO)
    fun ioDispatcher() = Dispatchers.IO

    @Provides
    @Named(InjectionNames.DISPATCHER_MAIN)
    fun mainDispatcher() = Dispatchers.Main

    @Provides
    @Named(InjectionNames.DISPATCHER_UNCONFINED)
    fun unconfinedDispatcher() = Dispatchers.Unconfined
}