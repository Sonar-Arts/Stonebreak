// Cloud shadows: how much direct sunlight reaches a point after crossing the
// cloud above it. The same Beer-Lambert rule the clouds are drawn with, so a
// thin wisp only dims the ground a little and a storm tower blacks out the sun.
// Requires cloud_field.glsl. Multiply into DIRECT light only (diffuse,
// specular), never ambient, exactly like csmShadowFactor.

uniform bool u_cloudShadowsEnabled;
uniform vec3 u_cloudSunDir;     // normalized, toward the sun
uniform float u_cloudShadowY;   // altitude the sun ray is projected to

// Penumbra: light scattering forward through a cloud (and the sun's disc)
// blurs its shadow, more the higher the cloud is above the ground. Radius in
// blocks = MIN + SPREAD * height of cloud above the point.
const float CLOUD_PENUMBRA_MIN = 4.0;
const float CLOUD_PENUMBRA_SPREAD = 0.12;
// A centre tap and a ring of six, as fractions of the radius: a hexagon so the
// ring covers the disc evenly with few taps.
const int CLOUD_SHADOW_RING = 6;
const float CLOUD_SHADOW_RING_R = 0.75;

// Sunlight through the one column at render-space xz above a point at height y.
float cloudShadowTap(vec2 xz, float y) {
    float clip;
    vec2 shift;
    CloudColumn col = cloudColumnLocal(vec3(xz.x, u_cloudShadowY, xz.y), clip, shift);
    if (col.density <= 0.0 || y >= col.reach) return 1.0;
    // Path length through the column along the sun ray; the unsnapped top so
    // the shadow has no 4-block steps.
    float path = (col.reach - max(col.base, y)) / u_cloudSunDir.y;
    return exp(-col.sigma * clip * path);
}

// The column cache (2026-11-20, CloudShadowCache): the same column values a tap computes - reach, base and
// sigma * clip (0 where no cloud) at the cloud layer - evaluated once per frame on a render-space grid, so a tap is
// one bilinear fetch instead of a full column lookup (relief, weather, bicubic cloud map, terrain flow). Taps
// outside the cached square (half a texel in) take the exact path. Receivers that never bind it stay exact.
uniform bool u_cloudCacheEnabled;
uniform sampler2D u_cloudShadowCache;
uniform vec2 u_cloudCacheOrigin;    // render-space xz of the cache's (0, 0) corner
uniform float u_cloudCacheInvSpan;  // 1 / (blocks per texel * texels per side)
uniform float u_cloudCacheHalfTexel; // half a texel in uv
// Near the camera the shadow stays exact (near ground, terrain relief and flow vary the column faster than the
// 4-block cache follows - measured up to ~0.1 of the shadow on near cliffs); the cache takes over from
// u_cloudCacheNear blocks (horizontal distance from u_cloudCacheEye), cross-faded over u_cloudCacheBlend blocks.
uniform vec3 u_cloudCacheEye;
uniform float u_cloudCacheNear;
uniform float u_cloudCacheBlend;

float cloudShadowTapCached(vec2 xz, float y) {
    vec2 uv = (xz - u_cloudCacheOrigin) * u_cloudCacheInvSpan;
    if (any(lessThan(uv, vec2(u_cloudCacheHalfTexel))) || any(greaterThan(uv, vec2(1.0 - u_cloudCacheHalfTexel)))) {
        return cloudShadowTap(xz, y);
    }
    vec3 c = texture(u_cloudShadowCache, uv).rgb;   // reach, base, sigma * clip
    if (c.z <= 0.0 || y >= c.x) return 1.0;
    float path = (c.x - max(c.y, y)) / u_cloudSunDir.y;
    return exp(-c.z * path);
}

float cloudShadowRing(vec3 renderPos, bool cached) {
    if (!u_cloudShadowsEnabled || u_cloudSunDir.y < 0.05) return 1.0;
    // Where this point's ray toward the sun crosses the cloud layer.
    float rise = max(u_cloudShadowY - renderPos.y, 0.0);
    vec2 xz = renderPos.xz + u_cloudSunDir.xz * (rise / u_cloudSunDir.y);
    // Average transmittance (not optical depth) over the penumbra disc, so a
    // cloud's edge fades out across it instead of cutting a line.
    float radius = (CLOUD_PENUMBRA_MIN + CLOUD_PENUMBRA_SPREAD * rise) * CLOUD_SHADOW_RING_R;
    float centre = cached ? cloudShadowTapCached(xz, renderPos.y) : cloudShadowTap(xz, renderPos.y);
    float sum = centre * 2.0;
    for (int i = 0; i < CLOUD_SHADOW_RING; i++) {
        float a = float(i) * (6.2831853 / float(CLOUD_SHADOW_RING));
        vec2 t = xz + radius * vec2(cos(a), sin(a));
        sum += cached ? cloudShadowTapCached(t, renderPos.y) : cloudShadowTap(t, renderPos.y);
    }
    return sum / float(CLOUD_SHADOW_RING + 2);
}

// The shadow straight from the cloud field (every tap a full column lookup).
float cloudShadowExact(vec3 renderPos) {
    return cloudShadowRing(renderPos, false);
}

// The shadow: exact near the camera, through the column cache (when bound) beyond u_cloudCacheNear.
float cloudShadow(vec3 renderPos) {
    if (!u_cloudCacheEnabled) return cloudShadowRing(renderPos, false);
    float d = length(renderPos.xz - u_cloudCacheEye.xz);
    if (d <= u_cloudCacheNear) return cloudShadowRing(renderPos, false);
    float far = cloudShadowRing(renderPos, true);
    if (d >= u_cloudCacheNear + u_cloudCacheBlend) return far;
    float w = smoothstep(u_cloudCacheNear, u_cloudCacheNear + u_cloudCacheBlend, d);
    return mix(cloudShadowRing(renderPos, false), far, w);
}
