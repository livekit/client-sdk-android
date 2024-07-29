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
