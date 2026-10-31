#version 330 core

// The Shade's ground fog over everything drawn before it, the sky and the opaque
// terrain, in one pass (GroundFog.renderScreen): each pixel takes its ray across the
// fog layer out to the surface the scene depth puts there, or on forever where it
// shows sky. Once per pixel, where fogging each surface as it drew ran the march for
// every layer of leaves the cutout pass overdrew. What draws after fogs itself.
// Blend (ONE, SRC_ALPHA): scene * transmittance + fog light.

#include "/shaders/world/ground_fog.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_sceneDepth;
uniform mat4 u_invViewProj;  // render space
uniform vec3 u_camPos;       // render space

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_invViewProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    float depth = texture(u_sceneDepth, vUv).r;
    if (depth >= 1.0) {
        fragColor = groundFog(u_camPos, normalize(worldAt(vUv, 1.0) - u_camPos), 1e9);
        return;
    }
    vec3 toPoint = worldAt(vUv, depth) - u_camPos;
    float dist = length(toPoint);
    fragColor = groundFog(u_camPos, toPoint / max(dist, 1e-4), dist);
}
