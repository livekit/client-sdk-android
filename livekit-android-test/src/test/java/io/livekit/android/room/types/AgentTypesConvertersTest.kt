/*
 * Copyright 2025-2026 LiveKit, Inc.
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

import com.beust.klaxon.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.reflect.KClass

/**
 * A test that ensures all the agent type converters account for all fields
 */
@RunWith(Parameterized::class)
class AgentTypesConvertersTest(
    val quickTypeClass: KClass<*>,
    val convertersMap: Map<String, *>,
    /** fields to ignore */
    val whitelist: List<String>,
    @Suppress("unused") val testName: String,
) {

    data class AgentTypesConvertersTestCase(
        val quickTypeClass: KClass<*>,
        val convertersMap: Map<String, *>,
        /** fields to ignore */
        val whitelist: List<String> = emptyList(),
    ) {
        fun toTestData(): Array<Any?> {
            return arrayOf(quickTypeClass, convertersMap, whitelist, quickTypeClass.simpleName)
        }
    }

    companion object {
        val testCases = listOf(
            AgentTypesConvertersTestCase(
                AgentAttributes::class,
                AGENT_ATTRIBUTES_CONVERSION,
            ),
            AgentTypesConvertersTestCase(
                TranscriptionAttributes::class,
                TRANSCRIPTION_ATTRIBUTES_CONVERSION,
            ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "Input: {3}")
        fun params(): List<Array<Any?>> {
            return testCases.map { it.toTestData() }
        }
    }

    @Test
    fun convertersVerify() {
        (AgentAttributes::class.members.first().annotations.first() as Json).name
        val jsonFields = quickTypeClass.members
            .map { it.annotations }
            .mapNotNull { it.firstOrNull { annotation -> annotation is Json } as Json? }
            .map { it.name }

        val converters = convertersMap.keys

        println("Converters")
        converters.forEach { println(it) }
        println()
        println("Json fields")
        jsonFields.forEach { println(it) }

        for (jsonField in jsonFields) {
            assertTrue("$jsonField not found!", converters.contains(jsonField))
        }

        for (converter in converters) {
            assertTrue("$converter not found!", jsonFields.contains(converter))
        }
    }
}
