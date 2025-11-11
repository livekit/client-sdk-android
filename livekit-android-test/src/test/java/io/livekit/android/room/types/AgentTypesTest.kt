package io.livekit.android.room.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTypesTest {

    // Some basic tests to ensure klaxon functionality.

    @Test
    fun testEmptyMapConversion() {
        val agentAttributes = AgentAttributes.fromMap(emptyMap<String, Any>())

        assertNull(agentAttributes.lkAgentInputs)
        assertNull(agentAttributes.lkAgentOutputs)
        assertNull(agentAttributes.lkAgentState)
        assertNull(agentAttributes.lkPublishOnBehalf)
    }

    @Test
    fun testSimpleMapConversion() {

        val map = mapOf(
            "lk.agent.state" to "idle",
            "lk.publish_on_behalf" to "agent_identity"
        )
        val agentAttributes = AgentAttributes.fromMap(map)

        assertNull(agentAttributes.lkAgentInputs)
        assertNull(agentAttributes.lkAgentOutputs)
        assertEquals(AgentSdkState.Idle, agentAttributes.lkAgentState)
        assertEquals("agent_identity", agentAttributes.lkPublishOnBehalf)
    }


    @Test
    fun testDeepMapConversion() {

        val map = mapOf(
            "lk.agent.inputs" to listOf("audio", "text"),
            "lk.agent.outputs" to listOf("audio"),
            "lk.agent.state" to "idle",
            "lk.publish_on_behalf" to "agent_identity"
        )
        val agentAttributes = AgentAttributes.fromMap(map)

        assertEquals(listOf(AgentInput.Audio, AgentInput.Text), agentAttributes.lkAgentInputs)
        assertEquals(listOf(AgentOutput.Audio), agentAttributes.lkAgentOutputs)
        assertEquals(AgentSdkState.Idle, agentAttributes.lkAgentState)
        assertEquals("agent_identity", agentAttributes.lkPublishOnBehalf)
    }
}