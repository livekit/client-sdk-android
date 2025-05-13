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

import livekit.org.webrtc.GlShader

private const val BOX_BLUR_SHADER_SOURCE = """#version 300 es
precision mediump float;

in vec2 texCoords;

uniform sampler2D u_texture;
uniform vec2 u_texelSize;    // 1.0 / texture size
uniform vec2 u_direction;    // (1.0, 0.0) for horizontal, (0.0, 1.0) for vertical
uniform float u_radius;      // blur radius in texels

out vec4 fragColor;

void main() {
    vec3 sum = vec3(0.0);
    float count = 0.0;

    // Limit radius to avoid excessive loop cost
    const int MAX_RADIUS = 16;
    int radius = int(min(float(MAX_RADIUS), u_radius));

    for (int i = -MAX_RADIUS; i <= MAX_RADIUS; ++i) {
        if (abs(i) > radius) continue;

        vec2 offset = u_direction * u_texelSize * float(i);
        sum += texture(u_texture, texCoords + offset).rgb;
        count += 1.0;
    }

    fragColor = vec4(sum / count, 1.0);
}
"""

internal fun createBoxBlurShader(): BlurShader {
    val shader = GlShader(CONSTANT_VERTEX_SHADER_SOURCE, BOX_BLUR_SHADER_SOURCE)

    return BlurShader(
        shader = shader,
        texMatrixLocation = 0,
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = 0,
        texture = shader.getUniformLocation("u_texture"),
        texelSize = shader.getUniformLocation("u_texelSize"),
        direction = shader.getUniformLocation("u_direction"),
        radius = shader.getUniformLocation("u_radius"),
    )
}
