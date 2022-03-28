package io.livekit.android.videoencodedecode

import android.content.Intent
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class VideoTest {

    val roomName = "browserStackTestRoom-${randomAlphanumericString(12)}"

    private fun createToken(name: String) = Jwts.builder()
        .setIssuer(BuildConfig.API_KEY)
        .setExpiration(Date(System.currentTimeMillis() + 1000 * 60 * 60 /* 1hour */))
        .setNotBefore(Date(0))
        .setSubject(name)
        .setId(name)
        .addClaims(
            mapOf(
                "video" to mapOf(
                    "roomJoin" to true,
                    "room" to roomName
                ),
                "name" to name
            )
        )
        .signWith(
            SecretKeySpec(BuildConfig.API_SECRET.toByteArray(), "HmacSHA256"),
            SignatureAlgorithm.HS256
        )
        .compact()

    private val token1 = createToken("phone1")

    private val token2 = createToken("phone2")

    @get:Rule
    val composeTestRule = createAndroidIntentComposeRule<CallActivity> { context ->
        Intent(context, CallActivity::class.java).apply {
            putExtra(
                CallActivity.KEY_ARGS,
                CallActivity.BundleArgs(
                    "wss://demo.livekit.cloud",
                    token1,
                    token2,
                )
            )
        }
    }

    @Test
    fun videoReceivedTest() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(20 * 1000L) {
            composeTestRule.onAllNodesWithTag(VIDEO_FRAME_INDICATOR).fetchSemanticsNodes(false, "")
                .size == 2
        }
    }
}
