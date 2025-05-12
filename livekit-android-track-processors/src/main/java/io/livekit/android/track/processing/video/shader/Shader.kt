package io.livekit.android.selfie.jsshader

import android.opengl.GLES20
import java.nio.FloatBuffer

object ShaderUtil {
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
            GLES20.glEnableVertexAttribArray(inPosLocation);
            GLES20.glVertexAttribPointer(
                inPosLocation,
                /* size= */ 2,
                /* type= */ GLES20.GL_FLOAT,
                /* normalized= */ false,
                /* stride= */ 0,
                inPosFloats,
            )
        }

        if (inTcFloats != null) {
            // Upload the texture coordinates.
            GLES20.glEnableVertexAttribArray(inTcLocation);
            GLES20.glVertexAttribPointer(
                inTcLocation,
                /* size= */ 2,
                /* type= */ GLES20.GL_FLOAT,
                /* normalized= */ false,
                /* stride= */ 0,
                inTcFloats,
            );
        }

        if (texMatrix != null) {
            // Upload the texture transformation matrix.
            GLES20.glUniformMatrix4fv(
                texMatrixLocation,
                /* count= */ 1,
                /* transpose= */ false,
                texMatrix,
                /* offset= */0,
            )
        }
    }
}
