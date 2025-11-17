/*
 * Copyright 2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
