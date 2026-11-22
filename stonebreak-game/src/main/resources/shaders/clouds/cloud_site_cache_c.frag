#version 330 core
// Second pass of the cloud site cache (CloudSiteCache), after B's mips are
// built: per texel, the anvil over the column (smooth B-spline read of the
// blurred storm mass, dragged downwind; cloudAnvilField) and the curl noise
// that bends the column's noise lookups. Baked here so the march reads both
// with one tap per sample instead of four mip taps and a dependent fetch.
// C = anvil amount, anvil top, curl x, curl z.
#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shape.glsl"

out vec4 outC;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float texels = u_siteCacheTexels;
    bool far = float(px.x) >= texels;
    vec2 local = vec2(float(px.x) - (far ? texels : 0.0), float(px.y)) + 0.5;
    vec2 u = (far ? u_siteCacheOrigin.zw : u_siteCacheOrigin.xy)
            + local * ((far ? u_siteCacheSpan.y : u_siteCacheSpan.x) / texels);
    vec2 flow = texelFetch(u_siteCacheB, px, 0).xy;
    vec2 curl = textureLod(u_curlNoise, (u + u_cloudOffset + flow) / u_curlTile, 0.0).rg;
    outC = vec4(cloudAnvilField(u), curl);
}
