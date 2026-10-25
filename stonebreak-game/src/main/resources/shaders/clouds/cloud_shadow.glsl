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

float cloudShadow(vec3 renderPos) {
    if (!u_cloudShadowsEnabled || u_cloudSunDir.y < 0.05) return 1.0;
    // Where this point's ray toward the sun crosses the cloud layer.
    float rise = max(u_cloudShadowY - renderPos.y, 0.0);
    vec2 xz = renderPos.xz + u_cloudSunDir.xz * (rise / u_cloudSunDir.y);
    // Average transmittance (not optical depth) over the penumbra disc, so a
    // cloud's edge fades out across it instead of cutting a line.
    float radius = (CLOUD_PENUMBRA_MIN + CLOUD_PENUMBRA_SPREAD * rise) * CLOUD_SHADOW_RING_R;
    float sum = cloudShadowTap(xz, renderPos.y) * 2.0;
    for (int i = 0; i < CLOUD_SHADOW_RING; i++) {
        float a = float(i) * (6.2831853 / float(CLOUD_SHADOW_RING));
        sum += cloudShadowTap(xz + radius * vec2(cos(a), sin(a)), renderPos.y);
    }
    return sum / float(CLOUD_SHADOW_RING + 2);
}
