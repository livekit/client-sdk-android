package io.livekit.android.room.participant


val Participant.isAgent
    get() = kind == Participant.Kind.AGENT

val Participant.agentState
    get() = agentAttributes.lkAgentState
