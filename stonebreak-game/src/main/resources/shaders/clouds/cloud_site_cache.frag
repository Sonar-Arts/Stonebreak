#version 330 core
// Builds the cloud site cache (CloudSiteCache): per texel, the site cloudSiteAt
// resolves for that render-space column - the cloud march then reads it with
// one bilinear tap per texture instead of a full column lookup per sample.
// Two grids side by side: x < u_siteCacheTexels = near, the rest = far.
// A = bottom, top, density, wetness; B = flow shift xz, anvil mass, anvil mass * top.
#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shape.glsl"

layout(location = 0) out vec4 outA;
layout(location = 1) out vec4 outB;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float texels = u_siteCacheTexels;
    bool far = float(px.x) >= texels;
    vec2 local = vec2(float(px.x) - (far ? texels : 0.0), float(px.y)) + 0.5;
    vec2 xz = (far ? u_siteCacheOrigin.zw : u_siteCacheOrigin.xy)
            + local * ((far ? u_siteCacheSpan.y : u_siteCacheSpan.x) / texels);
    CloudSite s = cloudSiteAt(xz);
    outA = vec4(s.bottom, s.top, s.col.density, s.col.wet);
    // Anvil mass: a grown storm's share of the cover (its tower ramp, as cloudSiteAt's).
    float storm = smoothstep(u_cloudWetSplits.z, u_cloudWetSplits.z + u_cloudTowerRamp, s.col.wet);
    float mass = s.col.density * s.weights.w * storm;
    outB = vec4(s.flow, mass, mass * s.top);
}
