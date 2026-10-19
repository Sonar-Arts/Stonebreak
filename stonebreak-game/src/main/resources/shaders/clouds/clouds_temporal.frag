#version 330 core

// Temporal accumulation of the half-resolution cloud march. The march dithers
// its first step differently every frame; blending each frame into the
// reprojected history averages that grain away, where a fixed per-pixel
// dither left a stripe pattern no spatial filter could hide.
//
// Reprojection is camera-relative, so a render-origin rebase between frames
// changes nothing: a pixel's point is its ray at the cloud slab's entry (a
// cloud's surface lies near it), moved into last frame's camera by the
// world-space camera delta. History is clamped to the current neighbourhood,
// so what reprojects wrongly (parallax, drift, disocclusion) cannot ghost.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_current;        // this frame's march: rgb light, a transmittance
uniform sampler2D u_history;        // last frame's result
uniform bool u_historyValid;
uniform mat4 u_invViewProj;         // render space, this frame
uniform vec3 u_camPos;              // render space, this frame
uniform mat4 u_prevViewProjRel;     // last frame's projection * view rotation (camera at origin)
uniform vec3 u_camDelta;            // camera world position now minus last frame's
uniform float u_slabBottom;
uniform float u_slabTop;

// Share of each frame that is history: about ten frames are averaged.
const float HISTORY_WEIGHT = 0.9;
// Distance a ray is reprojected at when the camera is inside the slab.
const float INSIDE_DISTANCE = 64.0;
const float FAR_DISTANCE = 2000.0;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 current = texelFetch(u_current, px, 0);
    if (!u_historyValid) { fragColor = current; return; }

    // Neighbourhood bounds of this frame's (noisy) samples.
    ivec2 size = textureSize(u_current, 0);
    vec4 lo = current, hi = current;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 c = texelFetch(u_current, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0);
            lo = min(lo, c);
            hi = max(hi, c);
        }
    }

    vec4 far = u_invViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w - u_camPos);
    float y = u_camPos.y;
    float t = FAR_DISTANCE;
    if (y < u_slabBottom) {
        if (rd.y > 1e-4) t = (u_slabBottom - y) / rd.y;
    } else if (y > u_slabTop) {
        if (rd.y < -1e-4) t = (u_slabTop - y) / rd.y;
    } else {
        t = INSIDE_DISTANCE;
    }
    t = min(t, FAR_DISTANCE);

    vec4 prevClip = u_prevViewProjRel * vec4(rd * t + u_camDelta, 1.0);
    vec2 prevUv = prevClip.xy / prevClip.w * 0.5 + 0.5;
    if (prevClip.w <= 0.0 || any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        fragColor = current;
        return;
    }
    vec4 history = clamp(texture(u_history, prevUv), lo, hi);
    fragColor = mix(current, history, HISTORY_WEIGHT);
}
