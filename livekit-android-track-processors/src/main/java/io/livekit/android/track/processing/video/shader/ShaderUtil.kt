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

package io.livekit.android.track.processing.video.shader

import android.opengl.GLES20
import java.nio.FloatBuffer

internal object ShaderUtil {
    fun loadCoordMatrix(
        inPosLocation: Int,
        inPosFloats: FloatBuffer? = null,
        inTcLocation: Int,
        inTcFloats: FloatBuffer? = null,
        texMatrixLocation: Int,
        texMatrix: FloatArray? = null,
    ) {
        if (inPosFloats != null) {
            // Upload the vertex coordinates.
            GLES20.glEnableVertexAttribArray(inPosLocation)
            GLES20.glVertexAttribPointer(
                inPosLocation,
                /* size= */
                2,
                /* type= */
                GLES20.GL_FLOAT,
                /* normalized= */
                false,
                /* stride= */
                0,
                inPosFloats,
            )
        }

        if (inTcFloats != null) {
            // Upload the texture coordinates.
            GLES20.glEnableVertexAttribArray(inTcLocation)
            GLES20.glVertexAttribPointer(
                inTcLocation,
                /* size= */
                2,
                /* type= */
                GLES20.GL_FLOAT,
                /* normalized= */
                false,
                /* stride= */
                0,
                inTcFloats,
            )
        }

        if (texMatrix != null) {
            // Upload the texture transformation matrix.
            GLES20.glUniformMatrix4fv(
                texMatrixLocation,
                /* count= */
                1,
                /* transpose= */
                false,
                texMatrix,
                /* offset= */
                0,
            )
        }
    }
}
