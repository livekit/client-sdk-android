package io.livekit.android.videoencodedecode

import androidx.compose.ui.test.junit4.ComposeTestRule
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
abstract class VideoTest {

    val roomName = "browserStackTestRoom-${randomAlphanumericString(12)}"

    companion object {
        val SERVER_URL = BuildConfig.SERVER_URL
        val API_KEY = BuildConfig.API_KEY
        val API_SECRET = BuildConfig.API_SECRET
    }

    private fun createToken(name: String) = Jwts.builder()
        .setIssuer(API_KEY)
        .setExpiration(Date(System.currentTimeMillis() + 1000 * 60 * 60))
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
            SecretKeySpec(API_SECRET.toByteArray(), "HmacSHA256"),
            SignatureAlgorithm.HS256
        )
        .compact()

    protected val token1 = createToken("phone1")
    protected val token2 = createToken("phone2")

    @get:Rule
    abstract val composeTestRule: ComposeTestRule

    @Test
    fun videoReceivedTest() {
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(20 * 1000L) {
            composeTestRule.onAllNodesWithTag(VIDEO_FRAME_INDICATOR).fetchSemanticsNodes(false, "").isNotEmpty()
        }
    }
}
