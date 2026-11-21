#version 330 core
// Builds the cloud-shadow column cache (CloudShadowCache, 2026-11-20): per texel, the column a cloud_shadow.glsl
// tap at that render-space xz would compute - reach, base and sigma * clip (0 where there is no cloud) at the
// cloud layer (u_cloudShadowY). One column lookup per texel per frame instead of eight per lit pixel.
#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shadow.glsl"

uniform vec2 u_cacheOrigin;   // render-space xz of texel (0, 0)'s corner
uniform float u_cacheTexel;   // blocks per texel

out vec4 fragColor;

void main() {
    vec2 xz = u_cacheOrigin + gl_FragCoord.xy * u_cacheTexel;   // gl_FragCoord = texel centre
    float clip;
    vec2 shift;
    CloudColumn col = cloudColumnLocal(vec3(xz.x, u_cloudShadowY, xz.y), clip, shift);
    fragColor = vec4(col.reach, col.base, col.density > 0.0 ? col.sigma * clip : 0.0, 1.0);
}
