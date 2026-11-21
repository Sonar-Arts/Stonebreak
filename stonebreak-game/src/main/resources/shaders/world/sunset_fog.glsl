// Distance fog toward the sky behind each fragment. In the evening the sky is
// gold toward the sun and, as the gold draws back, dusk on the far side (see
// SkyLight and sky.frag), so the fog fades distant land into whichever lies
// behind it instead of one colour all round. After sunset the Earth's shadow
// stands on the far horizon, and toward the sun its glow brightens the air:
// the land fades into those as well, as the sky beside it draws them.
uniform vec3 u_sunsetFog;       // sunset gold at the horizon × its strength (0 = none)
uniform float u_sunsetCoverage; // how far round from the sun the gold reaches
uniform vec2 u_sunFlat;         // the sun's compass direction (xz, unit)
uniform vec3 u_sunDir3;         // the sun's direction
uniform vec3 u_earthShadow;     // the Earth's shadow colour (sky.frag applyEarthShadow)
uniform float u_earthShadowHeight; // its edge's height opposite the sun (sine of elevation)
uniform float u_twilightBand;   // how strongly it shows (0 = none)
uniform vec3 u_sunAureole;      // the sky's glow round the sun (0 = none)

// SkyLight.SUNSET_SOFTNESS
const float SUNSET_FOG_SOFTNESS = 0.3;

// How golden the sky is toward compass direction `flatDir` (unit xz), 0..1:
// SkyLight.sunsetWeight × the gold's strength.
float sunsetFogWeight(vec2 flatDir) {
    float strength = max(u_sunsetFog.r, max(u_sunsetFog.g, u_sunsetFog.b));
    if (strength <= 0.0) return 0.0;
    float fromSun = 0.5 - 0.5 * dot(flatDir, u_sunFlat);
    return strength * (1.0 - smoothstep(u_sunsetCoverage - SUNSET_FOG_SOFTNESS,
            u_sunsetCoverage + SUNSET_FOG_SOFTNESS, fromSun));
}

vec3 horizonFogColor(vec3 clearFog, vec3 toFragment) {
    vec2 flatDir = toFragment.xz / max(length(toFragment.xz), 1e-4);
    vec3 fog = clearFog;
    float strength = max(u_sunsetFog.r, max(u_sunsetFog.g, u_sunsetFog.b));
    if (strength > 0.0) {
        fog = mix(fog, u_sunsetFog / strength, sunsetFogWeight(flatDir));
    }
    if (u_twilightBand > 0.0) {
        // Distant land lies on the horizon, under the shadow's edge wherever it has risen.
        float opposite = max(-dot(flatDir, u_sunFlat), 0.0);
        float inShadow = smoothstep(-0.06, 0.06, u_earthShadowHeight * opposite) * smoothstep(0.0, 0.3, opposite);
        fog = mix(fog, u_earthShadow, inShadow * u_twilightBand * 0.85);
    }
    // sky.frag getSunContribution's glow, along the level ray to the fragment.
    vec3 level = normalize(vec3(flatDir.x, 0.0, flatDir.y));
    float sunDot = dot(level, u_sunDir3);
    return fog + u_sunAureole * exp(-pow(max(1.0 - sunDot, 0.0) / 0.25, 1.5));
}
