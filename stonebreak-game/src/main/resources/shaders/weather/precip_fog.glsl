// Rain and snow fog: a haze around the camera while it is in the weather. It is
// as thick as the rain or snow where the camera is (WeatherEffects), and gone
// above the clouds. It is not hung under distant clouds as shafts: seen from
// outside, those read as hard-edged grey columns and added nothing (user's
// call, 2026-10-20), so a distant storm shows as its cloud and its falling rain.
//
// Beer-Lambert over at most u_precipFogDepth blocks: the sky is veiled by that
// depth, so the land at the horizon fades into the same haze the sky beside it has.

uniform bool u_precipFogEnabled;
uniform float u_precipFogDensity;  // extinction per block around the camera
uniform float u_precipFogDepth;    // deepest the fog reaches: the sky's veil
uniform vec3 u_precipFogColor;     // the light the fog scatters toward the eye

// Fog between eye and eye + dir * dist: rgb = in-scattered light, a = transmittance.
// A ray to the sky (any dist past u_precipFogDepth) takes the whole depth.
vec4 precipFog(vec3 eye, vec3 dir, float dist) {
    if (!u_precipFogEnabled) return vec4(0.0, 0.0, 0.0, 1.0);
    float t = exp(-u_precipFogDensity * min(dist, u_precipFogDepth));
    return vec4(u_precipFogColor * (1.0 - t), t);
}

// Fogs a surface `color` at eye + toPoint, then dissolves it by `horizon`
// (the distance fog, 0..1) into `horizonColor` fogged as the sky beside it is
// (precip_fog_sky.frag).
vec3 applyPrecipFog(vec3 color, vec3 eye, vec3 toPoint, vec3 horizonColor, float horizon) {
    float d = length(toPoint);
    vec4 f = precipFog(eye, toPoint / max(d, 1e-4), d);
    vec3 fogged = color * f.a + f.rgb;
    if (horizon <= 0.0) return fogged;
    vec4 sky = precipFog(eye, vec3(0.0, 1.0, 0.0), 1e9);
    return mix(fogged, horizonColor * sky.a + sky.rgb, horizon);
}
