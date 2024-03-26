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

package io.livekit.android.test.mock.dagger

import dagger.Module
import dagger.Provides
import io.livekit.android.dagger.InjectionNames
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Named

@Module
class TestCoroutinesModule(
    private val coroutineDispatcher: CoroutineDispatcher,
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
