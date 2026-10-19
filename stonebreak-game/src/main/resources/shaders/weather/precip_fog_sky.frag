#version 330 core

// Rain and snow fog over the sky, right after it is drawn: every pixel takes
// its full ray through the fog, and the terrain drawn over it fogs itself.
// Blend (ONE, SRC_ALPHA): sky * transmittance + fog light.

#include "/shaders/weather/precip_fog.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform mat4 u_invViewProj;  // render space
uniform vec3 u_camPos;       // render space

void main() {
    vec4 far = u_invViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w - u_camPos);
    fragColor = precipFog(u_camPos, rd, 1e9);
}
