package io.livekit.android.track.processing.video.shader

internal const val DEFAULT_VERTEX_SHADER_SOURCE = """#version 300 es
out vec2 texCoords;
in vec4 in_pos;
in vec4 in_tc;
uniform mat4 tex_mat;
void main() {
    gl_Position = in_pos;
    texCoords = (tex_mat * in_tc).xy;
}
"""

internal const val CONSTANT_VERTEX_SHADER_SOURCE = """#version 300 es
in vec2 in_pos;
out vec2 texCoords;

void main() {
    texCoords = (in_pos + 1.0) / 2.0;
    gl_Position = vec4(in_pos, 0, 1.0);
}
"""

internal const val VERTEX_SHADER_TEX_MAT_NAME = "tex_mat"
internal const val VERTEX_SHADER_TEX_COORD_NAME = "in_tc"
internal const val VERTEX_SHADER_POS_COORD_NAME = "in_pos"
