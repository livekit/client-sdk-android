package io.livekit.android.selfie.jsshader

import android.opengl.GLES20

object ShaderUtil {
    fun loadCoordMatrix(inPosLocation: Int, inTcLocation: Int, texMatrixLocation: Int, texMatrix: FloatArray) {
        // Upload the vertex coordinates.
        GLES20.glEnableVertexAttribArray(inPosLocation);
        GLES20.glVertexAttribPointer(inPosLocation, /* size= */ 2,
            /* type= */ GLES20.GL_FLOAT, /* normalized= */ false, /* stride= */ 0,
            FULL_RECTANGLE_BUFFER);

        // Upload the texture coordinates.
        GLES20.glEnableVertexAttribArray(inTcLocation);
        GLES20.glVertexAttribPointer(inTcLocation, /* size= */ 2,
            /* type= */ GLES20.GL_FLOAT, /* normalized= */ false, /* stride= */ 0,
            FULL_RECTANGLE_TEXTURE_BUFFER);

        // Upload the texture transformation matrix.
        GLES20.glUniformMatrix4fv(
            texMatrixLocation, 1 /* count= */, false /* transpose= */, texMatrix, 0 /* offset= */);
    }
}
