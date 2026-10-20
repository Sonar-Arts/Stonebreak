// Rain and snow fog as a volume in the world, not a haze around the camera:
// it hangs under every raining cloud, from the ground up into the cloud's underside,
// so a storm shows from far off as a grey curtain under its cloud and the air
// above the clouds stays clear. The density comes from the precipitation map
// (PrecipitationFog), which holds what each column of the cloud field drops.
//
// precipFog marches a view ray through it: Beer-Lambert extinction, in-scatter
// of the fog's light, front to back. The samples are spread evenly over the
// ray's span inside the fog, so they slide smoothly as the span changes and
// never band. Composite as color * a + rgb.

uniform bool u_precipFogEnabled;
uniform sampler2D u_precipFogMap;   // r: rate^0.7 (0 off the map), g: light through the cloud above, b: fog top, a: cloud base
uniform vec2 u_precipFogOrigin;     // render-space xz of the map's corner
uniform float u_precipFogSpan;      // blocks the map covers
uniform float u_precipFogReach;     // blocks from the camera the fog ends at (the terrain's horizon)
uniform float u_precipFogHeavy;     // extinction per block at the heaviest rate
uniform float u_precipFogTop;       // highest the fog reaches (PrecipitationFog.TOP): no fog above it
uniform float u_precipFogShadeMin;  // light left in fog under the thickest cloud
uniform vec3 u_precipFogColor;      // fog lit through a thin cloud

const int PRECIP_FOG_STEPS = 16;

// Fog between eye and eye + dir * dist (dir normalized): rgb = in-scattered
// light, a = transmittance.
vec4 precipFog(vec3 eye, vec3 dir, float dist) {
    if (!u_precipFogEnabled) return vec4(0.0, 0.0, 0.0, 1.0);
    // Clip to the fog layer, below the highest raining base...
    float t0 = 0.0, t1 = 1e9;
    if (dir.y > 1e-5) {
        t1 = (u_precipFogTop - eye.y) / dir.y;
    } else if (eye.y > u_precipFogTop) {
        if (dir.y > -1e-5) return vec4(0.0, 0.0, 0.0, 1.0);
        t0 = (u_precipFogTop - eye.y) / dir.y;
    }
    // ...and to the horizon around the camera, where the map fades it out.
    float across = length(dir.xz);
    if (across > 1e-5) t1 = min(t1, u_precipFogReach / across);
    t1 = min(dist, t1);
    if (t1 <= t0) return vec4(0.0, 0.0, 0.0, 1.0);

    float ds = (t1 - t0) / float(PRECIP_FOG_STEPS);
    vec3 light = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < PRECIP_FOG_STEPS; i++) {
        vec3 p = eye + dir * (t0 + (float(i) + 0.5) * ds);
        vec4 m = texture(u_precipFogMap, (p.xz - u_precipFogOrigin) / u_precipFogSpan);
        // Full up to the cloud's base, thinning out only inside the cloud
        // (PrecipitationFog.RISE), behind its own underside: thinned below the
        // base, the fog stopped short of the cloud in a visible line.
        float under = 1.0 - smoothstep(m.a, m.b, p.y);
        float ext = u_precipFogHeavy * m.r * under;
        if (ext <= 0.0) continue;
        float stepT = exp(-ext * ds);
        light += transmittance * (1.0 - stepT) * u_precipFogColor * mix(u_precipFogShadeMin, 1.0, m.g);
        transmittance *= stepT;
    }
    return vec4(light, transmittance);
}

// Fogs a surface `color` at eye + toPoint, then dissolves it by `horizon`
// (the distance fog, 0..1) into `horizonColor` fogged as the sky beside it is
// (precip_fog_sky.frag). The surface itself takes only the fog in front of it:
// marching on past it would lay the rain behind a peak over the peak, and from
// high up that ray dives into the rain layer behind, swallowing the peak.
vec3 applyPrecipFog(vec3 color, vec3 eye, vec3 toPoint, vec3 horizonColor, float horizon) {
    float d = length(toPoint);
    vec3 dir = toPoint / max(d, 1e-4);
    vec4 f = precipFog(eye, dir, d);
    vec3 fogged = color * f.a + f.rgb;
    if (horizon <= 0.0) return fogged;
    // The sky shows only above the horizon, so the sky beside a fragment below
    // it is the level ray in the same heading.
    vec3 level = vec3(dir.x, max(dir.y, 0.0), dir.z);
    level = dot(level, level) > 1e-8 ? normalize(level) : vec3(0.0, 1.0, 0.0);
    vec4 sky = precipFog(eye, level, 1e9);
    return mix(fogged, horizonColor * sky.a + sky.rgb, horizon);
}
