#version 330 core

// Additive: a bolt only ever adds light.

in float vAlpha;
out vec4 fragColor;

void main() {
    fragColor = vec4(vec3(0.82, 0.88, 1.0) * vAlpha, 1.0);
}
