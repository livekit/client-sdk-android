/*
 * Copyright 2026 LiveKit, Inc.
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

package io.livekit.android.room.datatrack

import io.livekit.android.test.BaseTest
import livekit.LivekitModels.DataTrackSchemaEncoding.WellKnownSchemaEncoding
import org.junit.Assert.assertEquals
import org.junit.Test

class DataTrackSchemaTest : BaseTest() {

    @Test
    fun blobKeyCarriesNameAndWellKnownEncoding() {
        val schema = DataTrackSchemaId("reading.v1", DataTrackSchemaEncoding.JsonSchema)
        val key = schema.blobKey

        assertEquals("reading.v1", key.schemaId.name)
        assertEquals(
            WellKnownSchemaEncoding.WELL_KNOWN_SCHEMA_ENCODING_JSON_SCHEMA,
            key.schemaId.encoding.wellKnown,
        )
    }

    @Test
    fun blobKeyUsesCustomFieldForCustomEncoding() {
        val schema = DataTrackSchemaId("x", DataTrackSchemaEncoding.Custom("myenc"))
        assertEquals("myenc", schema.blobKey.schemaId.encoding.custom)
    }
}
