#version 330 core

// The Shade's ground fog over the sky, right after it is drawn: every pixel takes
// its ray across the fog layer, and the terrain drawn over it fogs itself.
// Blend (ONE, SRC_ALPHA): sky * transmittance + fog light.

#include "/shaders/world/ground_fog.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform mat4 u_invViewProj;  // render space
uniform vec3 u_camPos;       // render space

void main() {
    vec4 far = u_invViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w - u_camPos);
    fragColor = groundFog(u_camPos, rd, 1e9);
}
