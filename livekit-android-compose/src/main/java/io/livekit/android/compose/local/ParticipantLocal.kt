package io.livekit.android.compose.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant


/**
 * Not to be confused with [LocalParticipant].
 */
val ParticipantLocal =
    compositionLocalOf<Participant> { throw IllegalStateException("No Participant object available. This should only be used within a ParticipantScope.") }

@Composable
fun ParticipantScope(
    participant: Participant,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        ParticipantLocal provides participant,
        content = content,
    )
}