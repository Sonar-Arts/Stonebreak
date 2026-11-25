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
uniform float u_aureoleWidth;   // its width, 1 - cos of the angle from the sun (wider in hazy air)
uniform float u_exposure;       // the final pass's exposure (SkyLight.exposure); 0 if never set

#include "/shaders/postfx/tone_curve.glsl"

// SkyLight.SUNSET_SOFTNESS
const float SUNSET_FOG_SOFTNESS = 0.3;
// The gold's scale heights and hues (SkyLight.SUNSET_H_AWAY / SUNSET_H_SUN,
// SUNSET_GOLD_LOW / SUNSET_GOLD_HIGH; sky.frag applySunset).
const float SUNSET_FOG_H_AWAY = 0.06;
const float SUNSET_FOG_H_SUN = 0.35;
const vec3 SUNSET_FOG_GOLD_LOW = vec3(1.0, 0.42, 0.18);
const vec3 SUNSET_FOG_GOLD_HIGH = vec3(1.0, 0.80, 0.48);

// The gold's scale height toward unit compass direction `flatDir`.
float sunsetFogHeight(vec2 flatDir) {
    return mix(SUNSET_FOG_H_AWAY, SUNSET_FOG_H_SUN, 0.5 + 0.5 * dot(flatDir, u_sunFlat));
}

// How golden the sky is in unit direction `dir`, 0..1: SkyLight.sunsetWeight ×
// the gold's strength. Round the sky it reaches u_sunsetCoverage from the sun;
// up the sky it falls off as exp(-y / H), so the zenith stays blue.
float sunsetFogWeight(vec3 dir) {
    float strength = max(u_sunsetFog.r, max(u_sunsetFog.g, u_sunsetFog.b));
    if (strength <= 0.0) return 0.0;
    vec2 flatDir = dir.xz / max(length(dir.xz), 1e-4);
    float fromSun = 0.5 - 0.5 * dot(dir.xz, u_sunFlat);
    float around = 1.0 - smoothstep(u_sunsetCoverage - SUNSET_FOG_SOFTNESS,
            u_sunsetCoverage + SUNSET_FOG_SOFTNESS, fromSun);
    return strength * around * exp(-max(dir.y, 0.0) / sunsetFogHeight(flatDir));
}

// The gold's colour in unit direction `dir`: deep orange on the horizon (and over
// the land below it, which meets the sky there), paling with height. u_sunsetFog
// carries the weather's tint, applied over the whole grade.
vec3 sunsetFogGold(vec3 dir) {
    float strength = max(u_sunsetFog.r, max(u_sunsetFog.g, u_sunsetFog.b));
    vec2 flatDir = dir.xz / max(length(dir.xz), 1e-4);
    float t = clamp(max(dir.y, 0.0) / sunsetFogHeight(flatDir), 0.0, 1.0);
    vec3 tint = u_sunsetFog / max(strength * SUNSET_FOG_GOLD_LOW, vec3(1e-4));
    return mix(SUNSET_FOG_GOLD_LOW, SUNSET_FOG_GOLD_HIGH, t) * tint;
}

vec3 horizonFogColor(vec3 clearFog, vec3 toFragment) {
    vec3 dir = toFragment / max(length(toFragment), 1e-4);
    vec2 flatDir = toFragment.xz / max(length(toFragment.xz), 1e-4);
    vec3 fog = clearFog;
    float weight = sunsetFogWeight(dir);
    if (weight > 0.0) {
        fog = mix(fog, sunsetFogGold(dir), weight);
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
    return fog + u_sunAureole * exp(-pow(max(1.0 - sunDot, 0.0) / u_aureoleWidth, 1.5));
}

// The fog colours above are painted as what the screen shows, as sky.frag paints the
// sky (which converts its colour for the tone map). So distance fog is mixed on screen,
// as it always was: take the fragment to its screen colour, fog it there, and bring the
// result back. Unfogged it comes back unchanged (to within the curve's last 0.5% below
// white), fully fogged it is exactly the sky behind it, and part-way it blends as before.
// (Mixing the converted fog colour in the scene instead runs away wherever the fog is
// near white in a channel, as the sunset's red is: that channel's scene value is huge.)
float fogExposure() {
    return u_exposure > 0.0 ? u_exposure : 1.0;
}

vec3 fogToScreen(vec3 scene) {
    return sceneToDisplay(scene, fogExposure());
}

vec3 fogFromScreen(vec3 display) {
    return displayToScene(display, fogExposure());
}
