package io.livekit.android.videoencodedecode.test.test

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.livekit.android.videoencodedecode.CallActivity
import io.livekit.android.videoencodedecode.VideoTest
import io.livekit.android.videoencodedecode.createAndroidIntentComposeRule

class H264SimulcastVideoTest : VideoTest() {
    override val composeTestRule: ComposeTestRule = createAndroidIntentComposeRule<CallActivity> { context ->
        Intent(context, CallActivity::class.java).apply {
            putExtra(
                CallActivity.KEY_ARGS,
                CallActivity.BundleArgs(
                    SERVER_URL,
                    token1,
                    token2,
                    useDefaultVideoEncoderFactory = false,
                    codecWhiteList = listOf("H264")
                )
            )
        }
    }
}
