package io.livekit.android.test.mock.room.util

import io.livekit.android.room.util.ConnectionWarmer
import okhttp3.Response

class MockConnectionWarmer : ConnectionWarmer {
    override suspend fun fetch(url: String): Response {
        return Response.Builder().code(200).build()
    }
}
