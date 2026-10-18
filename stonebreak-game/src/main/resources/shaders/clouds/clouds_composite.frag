#version 330 core

// Lays the half-resolution cloud buffer over the scene. Blend (ONE, SRC_ALPHA):
// scene * transmittance + premultiplied cloud light.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_clouds;

void main() {
    fragColor = texture(u_clouds, vUv);
}
