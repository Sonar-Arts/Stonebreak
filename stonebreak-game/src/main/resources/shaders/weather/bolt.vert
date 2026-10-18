#version 330 core

// Lightning bolt ribbons, built on the CPU in render space.

layout (location = 0) in vec3 aPos;
layout (location = 1) in float aAlpha;

uniform mat4 uProjection;
uniform mat4 uView;

out float vAlpha;

void main() {
    vAlpha = aAlpha;
    gl_Position = uProjection * uView * vec4(aPos, 1.0);
}
