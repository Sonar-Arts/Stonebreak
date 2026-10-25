#version 330 core

// How much of the sky's light reaches the eye at each pixel, for the god rays:
// what the cloud in front lets through times what the rain and snow fog along
// the whole ray lets through. Without the fog, sky seen under a storm deck
// through a kilometre of rain sent full shafts of sunlight across it.

#include "/shaders/weather/precip_fog.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform mat4 u_invViewProj;  // render space
uniform vec3 u_camPos;       // render space
uniform sampler2D u_cloudTransmittance; // alpha = light the clouds let through
uniform bool u_hasClouds;

void main() {
    vec4 far = u_invViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w - u_camPos);
    float t = precipFog(u_camPos, rd, 1e9).a;
    if (u_hasClouds) t *= texture(u_cloudTransmittance, vUv).a;
    fragColor = vec4(t);
}
