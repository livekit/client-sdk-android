/*
 * Copyright 2024 LiveKit, Inc.
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

package io.livekit.android.room.util

import io.livekit.android.util.executeAsync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject

interface ConnectionWarmer {
    suspend fun fetch(url: String): Response
}

class OkHttpConnectionWarmer
@Inject
constructor(
    private val okHttpClient: OkHttpClient,
) : ConnectionWarmer {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun fetch(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .method("HEAD", null)
            .build()
        return okHttpClient.newCall(request)
            .executeAsync()
    }
}
