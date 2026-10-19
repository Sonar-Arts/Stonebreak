#version 330 core

// Rain and snow fall from the cloud: one thin lane per slot of a grid fixed in
// CLOUD space, so the pattern rides the wind with the cloud above it. Each
// lane asks the cloud field (the same one the clouds are drawn from) how much
// falls at its own position, so a cloud's edge crossing a block rains on part
// of it. A lane is a quad turned to face the camera about the vertical axis,
// from its ground up to the cloud base (or the top of the falling range); a
// lane with nothing to drop collapses to nothing.

#include "/shaders/clouds/cloud_field.glsl"

layout (location = 0) in vec2 aCorner;   // (0..1, 0..1)

uniform mat4 uProjection;
uniform mat4 uView;
uniform vec3 uCameraPos;    // render space
uniform float uLaneSpacing; // blocks between lane slots, in cloud space
uniform int uLaneGrid;      // slots per side of the grid around the camera
uniform int uLanePeriod;    // slots per wrap of u_cloudOffset: the slot hash repeats with it
uniform vec2 uLaneWidth;    // quad width, blocks: rain, snow
uniform float uRadius;      // lanes reach this far from the camera
uniform float uBelow;       // and fall no further than this below it...
uniform float uAbove;       // ...nor start higher than this above it
// RG: ground height (very low where unknown), snow (0 or 1), per block column;
// texel (i, j) is the block whose corner is uGroundOrigin + (i, j).
uniform sampler2D uGround;
uniform vec2 uGroundOrigin; // render space

out vec2 vUv;        // x across the lane, y = world height
out vec2 vParams;    // intensity, snow
out float vSeed;
out float vDist;

// Integer hash (pcg2d, Jarzynski & Olano 2020).
uvec2 pcg2d(uvec2 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v = v ^ (v >> 16u);
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v = v ^ (v >> 16u);
    return v;
}

void collapse() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // every corner at one point outside the view
    vUv = vec2(0.0);
    vParams = vec2(0.0);
    vSeed = 0.0;
    vDist = 0.0;
}

void main() {
    ivec2 cell = ivec2(gl_InstanceID % uLaneGrid, gl_InstanceID / uLaneGrid) - uLaneGrid / 2;
    ivec2 slot = ivec2(floor((uCameraPos.xz + u_cloudOffset) / uLaneSpacing)) + cell;
    // Wrapped with the offset, so a lane keeps its seed when the offset wraps.
    // (GLSL's % is undefined for negative operands, so floor division.)
    ivec2 wrapped = slot - uLanePeriod * ivec2(floor(vec2(slot) / float(uLanePeriod)));
    uvec2 h = pcg2d(uvec2(wrapped));
    vec2 jitter = vec2(h >> 8u) / 16777216.0;
    vec2 lane = (vec2(slot) + jitter) * uLaneSpacing - u_cloudOffset; // render space
    float seed = float((h.x ^ h.y) >> 8u) / 16777216.0;

    vec2 toCam = uCameraPos.xz - lane;
    float dist = length(toCam);
    if (dist > uRadius) { collapse(); return; }

    vec2 shift;
    CloudColumn col = cloudColumnAt(lane, shift);
    float intensity = cloudPrecipitation(col);
    // Light rain fills fewer lanes.
    if (intensity <= 0.0 || fract(seed * 31.7) > intensity + 0.25) { collapse(); return; }

    ivec2 g = clamp(ivec2(floor(lane - uGroundOrigin)), ivec2(0), textureSize(uGround, 0) - 1);
    vec2 ground = texelFetch(uGround, g, 0).rg;
    float bottom = max(ground.r, uCameraPos.y - uBelow);
    // Falls from the cloud base: nothing above it, nothing when the camera is above it.
    float top = min(uCameraPos.y + uAbove, col.base);
    if (top <= bottom) { collapse(); return; }

    float snow = ground.g;
    vec2 facing = dist > 1e-3 ? toCam / dist : vec2(0.0, 1.0);
    vec2 right = vec2(-facing.y, facing.x);
    float width = mix(uLaneWidth.x, uLaneWidth.y, snow);
    float y = mix(bottom, top, aCorner.y);
    vec2 xz = lane + right * (aCorner.x - 0.5) * width;

    vUv = vec2(aCorner.x, y);
    vParams = vec2(intensity, snow);
    vSeed = seed;
    vDist = dist;
    gl_Position = uProjection * uView * vec4(xz.x, y, xz.y, 1.0);
}
