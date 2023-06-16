package io.livekit.android.videoencodedecode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import io.livekit.android.composesample.ui.theme.BlueMain
import io.livekit.android.composesample.ui.theme.NoVideoBackground
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.sample.common.R
import io.livekit.android.util.flow

/**
 * Widget for displaying a participant.
 */
@Composable
fun ParticipantItem(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    isSpeaking: Boolean,
) {
    val identity by participant::identity.flow.collectAsState()
    val videoTracks by participant::videoTracks.flow.collectAsState()
    val audioTracks by participant::audioTracks.flow.collectAsState()
    val identityBarPadding = 4.dp
    ConstraintLayout(
        modifier = modifier.background(NoVideoBackground)
            .run {
                if (isSpeaking) {
                    border(2.dp, BlueMain)
                } else {
                    this
                }
            }
    ) {
        val (videoItem, identityBar, identityText, muteIndicator, connectionIndicator) = createRefs()

        VideoItemTrackSelector(
            room = room,
            participant = participant,
            modifier = Modifier.constrainAs(videoItem) {
                top.linkTo(parent.top)
                bottom.linkTo(parent.bottom)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                width = Dimension.fillToConstraints
                height = Dimension.fillToConstraints
            }
        )

        Surface(
            color = Color(0x80000000),
            modifier = Modifier.constrainAs(identityBar) {
                bottom.linkTo(parent.bottom)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                width = Dimension.fillToConstraints
                height = Dimension.value(30.dp)
            }
        ) {}

        Text(
            text = identity ?: "",
            color = Color.White,
            modifier = Modifier.constrainAs(identityText) {
                top.linkTo(identityBar.top)
                bottom.linkTo(identityBar.bottom)
                start.linkTo(identityBar.start, margin = identityBarPadding)
                end.linkTo(muteIndicator.end, margin = 10.dp)
                width = Dimension.fillToConstraints
                height = Dimension.wrapContent
            },
        )

        val isMuted = audioTracks.none { (pub) -> pub.track != null && !pub.muted }

        if (isMuted) {
            Icon(
                painter = painterResource(id = R.drawable.outline_mic_off_24),
                contentDescription = "",
                tint = Color.Red,
                modifier = Modifier.constrainAs(muteIndicator) {
                    top.linkTo(identityBar.top)
                    bottom.linkTo(identityBar.bottom)
                    end.linkTo(identityBar.end, margin = identityBarPadding)
                }
            )
        }

        val connectionQuality by participant::connectionQuality.flow.collectAsState()

        val connectionIcon = when (connectionQuality) {
            ConnectionQuality.EXCELLENT -> R.drawable.wifi_strength_4
            ConnectionQuality.GOOD -> R.drawable.wifi_strength_3
            ConnectionQuality.POOR -> R.drawable.wifi_strength_alert_outline
            ConnectionQuality.UNKNOWN -> R.drawable.wifi_strength_alert_outline
        }

        if (connectionQuality == ConnectionQuality.POOR) {
            Icon(
                painter = painterResource(id = connectionIcon),
                contentDescription = "",
                tint = Color.Red,
                modifier = Modifier
                    .constrainAs(connectionIndicator) {
                        top.linkTo(parent.top, margin = identityBarPadding)
                        end.linkTo(parent.end, margin = identityBarPadding)
                    }
                    .alpha(0.5f)
            )
        }
    }
}
