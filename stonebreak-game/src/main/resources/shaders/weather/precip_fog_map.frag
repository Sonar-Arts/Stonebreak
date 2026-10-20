#version 330 core

// One texel of the precipitation map (PrecipitationFog): what the cloud column
// over it drops, how much light comes through that cloud, and the span over
// which the fog under it thins out: from the underside of the cloud as drawn
// (cloud_shape.glsl) up into it, so the fog follows the cloud's ragged bottom
// instead of a flat altitude, and its end hides inside the cloud. Read by
// precip_fog.glsl.

#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shape.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform vec2 u_mapOrigin;   // render-space xz of the map's corner
uniform float u_mapSpan;    // blocks the map covers
uniform float u_mapTexel;   // blocks per texel
uniform vec2 u_mapCentre;   // render-space xz the map is centred on
uniform float u_mapReach;   // blocks from the centre the fog ends at (the terrain's horizon)
uniform float u_rateExponent;
uniform float u_topRise;    // blocks the fog reaches up into its cloud
uniform float u_undersideReach; // highest above its column's base the underside is looked for
// Optical depth of the cloud above, scaled down: scattered light gets through
// thick cloud far better than the direct beam exp(-tau) would let it.
const float DIFFUSE_DEPTH = 0.5;
// Rain spreads and drifts as it falls, so a shaft has soft sides rather than
// the cloud's crisp outline: the map averages a cross of columns this far apart.
// Kept narrow, so the shaft stays under its cloud: wider, the fog's flat top
// showed beside the cloud and neighbouring showers merged into one sheet.
const float SPREAD = 12.0;
// Rain falls in curtains, not as an even sheet: bands about this many blocks
// across, carried in cloud space so they ride the wind with their cloud...
const float CURTAIN_SCALE = 160.0;
// ...thinning the rain between them to this share. Heavy rain closes the gaps.
const float CURTAIN_GAP = 0.2;
const vec2 TAPS[5] = vec2[5](vec2(0.0), vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0));
// The fog's top dips by up to this many blocks, unevenly across the land (two
// octaves, about 600 and 200 blocks across), so where it shows it is not a flat
// sheet cut off at one altitude. Less than u_topRise, so it never dips under the base.
const float TOP_DIP = 16.0;
const float TOP_SCALE = 600.0;
// Shortest the fade above the underside gets, blocks: where the noise carves
// the column clear there is no cloud above it to fade into.
const float MIN_FADE = 4.0;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0)), u.x), u.y);
}

void main() {
    vec2 xz = u_mapOrigin + vUv * u_mapSpan;
    float rate = 0.0, light = 0.0, base = 0.0, thick = 0.0;
    // Underside of the raining columns and the cloud above it, weighted by what each drops.
    float under = 0.0, above = 0.0;
    for (int i = 0; i < 5; i++) {
        vec2 tap = xz + TAPS[i] * SPREAD, shift;
        CloudColumn col = cloudColumnAt(tap, shift);
        float r = cloudPrecipitation(col);
        rate += r;
        light += col.density > 0.0 ? exp(-DIFFUSE_DEPTH * col.sigma * (col.top - col.base)) : 1.0;
        base += col.base;
        thick += col.top - col.base;
        if (r > 0.0) {
            float u = cloudUnderside(tap, col, min(col.top, col.base + u_undersideReach), u_mapTexel, false);
            under += r * u;
            above += r * (col.top - u);
        }
    }
    // A dry texel keeps its cloud's base, so filtering toward a raining one
    // doesn't drag that one's underside down.
    float bottom = rate > 0.0 ? under / rate : base / 5.0;
    float room = rate > 0.0 ? above / rate : thick / 5.0;
    rate /= 5.0;
    vec2 c = (xz + u_cloudOffset) / CURTAIN_SCALE;
    // Two octaves, so the curtains have ragged edges rather than round outlines.
    float curtain = 0.65 * valueNoise(c) + 0.35 * valueNoise(c * 3.1 + 41.0);
    rate *= mix(mix(CURTAIN_GAP, 1.0, smoothstep(0.3, 0.7, curtain)), 1.0, rate * rate);
    // Fades out before the horizon, so nothing ends in a wall there.
    float radius = u_mapReach;
    rate *= 1.0 - smoothstep(0.8 * radius, radius, distance(xz, u_mapCentre));
    // In world space, so the dips stay put on the land as the render origin moves.
    vec2 w = (xz + u_worldOffset) / TOP_SCALE;
    float dip = 0.65 * valueNoise(w) + 0.35 * valueNoise(w * 3.0 + 17.0);
    // Never above the cloud's own top, so no fog pokes out over a thin cloud.
    float top = bottom + max(min(u_topRise - TOP_DIP * dip, room), MIN_FADE);
    fragColor = vec4(pow(rate, u_rateExponent), light / 5.0, top, bottom);
}
