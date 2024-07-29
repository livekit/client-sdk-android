package io.livekit.android.room

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.util.LKLog
import io.livekit.android.util.executeAsync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * @suppress
 */
class RegionUrlProvider
@AssistedInject
constructor(
    @Assisted val serverUrl: URI,
    @Assisted var token: String,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private var regionSettings: RegionSettings? = null
    private var lastUpdateAt: Long = 0L
    private var settingsCacheTimeMs = 30000
    private var attemptedRegions = mutableSetOf<RegionInfo>()

    fun isLKCloud() = serverUrl.isLKCloud()

    @Throws(RoomException.ConnectException::class)
    suspend fun getNextBestRegionUrl() = coroutineScope {
        if (!isLKCloud()) {
            throw IllegalStateException("Region availability is only supported for LiveKit Cloud domains")
        }
        if (regionSettings == null || SystemClock.elapsedRealtime() - lastUpdateAt > settingsCacheTimeMs) {
            fetchRegionSettings()
        }
        val regions = regionSettings?.regions ?: return@coroutineScope null
        val regionsLeft = regions.filter { region ->
            !attemptedRegions.any { attempted -> attempted.url == region.url }
        }

        if (regionsLeft.isEmpty()) {
            return@coroutineScope null
        }

        val nextRegion = regionsLeft.first()
        attemptedRegions.add(nextRegion)
        LKLog.d { "next region: $nextRegion" }
        return@coroutineScope nextRegion.url
    }

    @Throws(RoomException.ConnectException::class)
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fetchRegionSettings(): RegionSettings? {
        val request = Request.Builder()
            .url(serverUrl.getCloudConfigUrl("/regions").toString())
            .header("Authorization", "Bearer $token")
            .build()
        val bodyString = okHttpClient.newCall(request)
            .executeAsync()
            .use { response ->
                if (!response.isSuccessful) {
                    throw RoomException.ConnectException("Could not fetch region settings: ${response.code} ${response.message}")
                }
                return@use response.body?.string() ?: return null
            }

        LKLog.e { bodyString }
        return json.decodeFromString<RegionSettings>(bodyString).also {
            regionSettings = it
            lastUpdateAt = SystemClock.elapsedRealtime()
        }
    }

    fun clearAttemptedRegions() {
        attemptedRegions.clear()
    }

    @AssistedFactory
    interface Factory {
        fun create(serverUrl: URI, token: String): RegionUrlProvider
    }
}


internal fun URI.isLKCloud() = regionUrlProviderTesting || host.endsWith(".livekit.cloud") || host.endsWith(".livekit.run")

internal fun URI.getCloudConfigUrl(appendPath: String = ""): URI {
    val scheme = this.scheme.replace("ws", "http")
    return URI(
        scheme,
        null,
        this.host,
        this.port,
        "/settings$appendPath",
        null,
        null,
    )
}

private var regionUrlProviderTesting = false

@VisibleForTesting
fun setRegionUrlProviderTesting(enable: Boolean) {
    regionUrlProviderTesting = enable
}

/**
 * @suppress
 */
@Serializable
data class RegionSettings(val regions: List<RegionInfo>)

/**
 * @suppress
 */
@Serializable
data class RegionInfo(val region: String, val url: String, val distance: Long)
