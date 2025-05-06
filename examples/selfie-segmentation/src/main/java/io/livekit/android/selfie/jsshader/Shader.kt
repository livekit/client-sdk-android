package io.livekit.android.selfie.jsshader

import android.opengl.GLES20
import java.nio.FloatBuffer

object ShaderUtil {
    fun loadCoordMatrix(
        inPosLocation: Int,
        inPosFloats: FloatBuffer = FULL_RECTANGLE_BUFFER,
        inTcLocation: Int,
        inTcFloats: FloatBuffer = FULL_RECTANGLE_TEXTURE_BUFFER,
        texMatrixLocation: Int,
        texMatrix: FloatArray,
    ) {
        // Upload the vertex coordinates.
        GLES20.glEnableVertexAttribArray(inPosLocation);
        GLES20.glVertexAttribPointer(
            inPosLocation,
            /* size= */ 2,
            /* type= */ GLES20.GL_FLOAT,
            /* normalized= */ false,
            /* stride= */ 0,
            inPosFloats,
        );

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

        // Upload the texture transformation matrix.
        GLES20.glUniformMatrix4fv(
            texMatrixLocation,
            /* count= */ 1,
            /* transpose= */ false,
            texMatrix,
            /* offset= */0,
        );
    }
}
