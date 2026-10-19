#version 330 core

// Lays the half-resolution cloud buffer over the scene. Blend (ONE, SRC_ALPHA):
// scene * transmittance + premultiplied cloud light.
//
// The upsample is depth-aware. A full-resolution pixel whose scene surface is
// nearer than the cloud slab gets no cloud at all; any other takes a tent of its
// 3x3 cloud texels, each weighted by how close the depth it marched to is to this
// pixel's own. A plain filter spread cloud-free texels off a block's silhouette
// onto the sky beside it, which drew a clear rim around every block.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_clouds;
uniform sampler2D u_sceneDepth;     // full resolution
uniform mat4 u_invViewProj;         // render space
uniform vec3 u_camPos;              // render space
uniform float u_slabBottom;
uniform float u_slabTop;

// Distance standing in for "no surface" (sky), so differences stay finite.
const float SKY_DISTANCE = 1e6;
// Relative distance difference at which a texel's weight falls to 1/e.
const float DEPTH_TOLERANCE = 0.05;

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_invViewProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

float distanceAt(vec2 uv, float depth) {
    return depth >= 1.0 ? SKY_DISTANCE : min(length(worldAt(uv, depth) - u_camPos), SKY_DISTANCE);
}

// The depth clouds_march.frag stopped half-res texel `px` at: the farthest of its
// 2x2 full-resolution texels.
float marchedDepth(ivec2 px) {
    ivec2 last = textureSize(u_sceneDepth, 0) - 1;
    ivec2 p = px * 2;
    return max(max(texelFetch(u_sceneDepth, min(p, last), 0).r,
                   texelFetch(u_sceneDepth, min(p + ivec2(1, 0), last), 0).r),
               max(texelFetch(u_sceneDepth, min(p + ivec2(0, 1), last), 0).r,
                   texelFetch(u_sceneDepth, min(p + ivec2(1, 1), last), 0).r));
}

// Distance along the view ray at which it enters the cloud slab.
float slabEntry(vec3 rd) {
    float y = u_camPos.y;
    if (y < u_slabBottom) return rd.y > 1e-5 ? (u_slabBottom - y) / rd.y : SKY_DISTANCE;
    if (y > u_slabTop) return rd.y < -1e-5 ? (u_slabTop - y) / rd.y : SKY_DISTANCE;
    return 0.0;
}

void main() {
    vec2 fullSize = vec2(textureSize(u_sceneDepth, 0));
    float depth = texelFetch(u_sceneDepth, ivec2(vUv * fullSize), 0).r;
    float d = distanceAt(vUv, depth);
    vec3 rd = normalize(worldAt(vUv, 1.0) - u_camPos);
    // In front of every cloud: the scene shows unchanged.
    if (d < slabEntry(rd)) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    ivec2 halfSize = textureSize(u_clouds, 0);
    vec2 pos = vUv * vec2(halfSize);         // in half-res texels
    ivec2 centre = ivec2(pos);
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    vec4 nearest = vec4(0.0, 0.0, 0.0, 1.0);
    float nearestDiff = 1e30;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 px = clamp(centre + ivec2(x, y), ivec2(0), halfSize - 1);
            vec2 texelUv = (vec2(px) + 0.5) / vec2(halfSize);
            vec2 off = abs(vec2(px) + 0.5 - pos);
            float tent = max(0.0, 1.25 - off.x) * max(0.0, 1.25 - off.y);
            float diff = abs(distanceAt(texelUv, marchedDepth(px)) - d) / max(d, 1.0);
            vec4 c = texelFetch(u_clouds, px, 0);
            float w = tent * exp(-diff / DEPTH_TOLERANCE);
            sum += c * w;
            weight += w;
            if (diff < nearestDiff) { nearestDiff = diff; nearest = c; }
        }
    }
    // No texel marched to this pixel's depth (a sliver thinner than a cloud
    // texel): the closest match is still better than none.
    fragColor = weight > 1e-4 ? sum / weight : nearest;
}
