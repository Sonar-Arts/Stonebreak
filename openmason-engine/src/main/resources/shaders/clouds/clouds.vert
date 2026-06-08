#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in float aShade;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

out float vShade;
out vec3 vWorldPos;

void main() {
    vec4 worldPos = modelMatrix * vec4(aPos, 1.0);
    vWorldPos = worldPos.xyz;
    vShade = aShade;
    gl_Position = projectionMatrix * viewMatrix * worldPos;
}
