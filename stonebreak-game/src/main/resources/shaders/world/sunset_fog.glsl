// Distance fog toward the sky behind each fragment. In the evening the sky is
// gold toward the sun and, as the gold draws back, dusk on the far side (see
// SkyLight and sky.frag), so the fog fades distant land into whichever lies
// behind it instead of one colour all round.
uniform vec3 u_sunsetFog;       // sunset gold at the horizon × its strength (0 = none)
uniform float u_sunsetCoverage; // how far round from the sun the gold reaches
uniform vec2 u_sunFlat;         // the sun's compass direction (xz, unit)

// SkyLight.SUNSET_SOFTNESS
const float SUNSET_FOG_SOFTNESS = 0.3;

vec3 horizonFogColor(vec3 clearFog, vec3 toFragment) {
    float strength = max(u_sunsetFog.r, max(u_sunsetFog.g, u_sunsetFog.b));
    if (strength <= 0.0) {
        return clearFog;
    }
    vec2 flatDir = toFragment.xz / max(length(toFragment.xz), 1e-4);
    float fromSun = 0.5 - 0.5 * dot(flatDir, u_sunFlat);
    float weight = 1.0 - smoothstep(u_sunsetCoverage - SUNSET_FOG_SOFTNESS,
            u_sunsetCoverage + SUNSET_FOG_SOFTNESS, fromSun);
    return mix(clearFog, u_sunsetFog / strength, weight * strength);
}
