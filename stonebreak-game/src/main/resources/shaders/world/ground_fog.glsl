// The Shade's night ground fog (GroundFog.java): a layer u_groundFogDepth blocks deep
// lying on Shade ground, thinning out with height above it. Where the Shade is comes
// from a camera-centred map (ShadeFogMap): R = Shade coverage 0..1, with the fade over
// the map's outer eighth already multiplied in (GroundFog), G = ground height.
//
// Beer-Lambert marched across the layer between the eye and the point: the ray is
// clipped to the box the fog can occupy (the Shade's extent on the map, between the
// lowest and highest fog), then sampled at jittered steps spaced quadratically, densest
// near the eye where the fog's shape shows. Short rays take fewer steps, never spaced
// wider than a 20-block ray's; the march stops once nothing behind could show through.
//
// Standing in the fog, the eye also sees at least u_groundFogHaze blocks of it in
// every direction: the layer alone is a few blocks deep straight up, and the sky
// overhead stayed clear from inside it. The two are not added (that fogged the
// ground twice); the ray takes whichever is deeper.

uniform bool u_groundFogEnabled;
uniform sampler2D u_groundFogMap;
uniform vec3 u_groundFogOrigin;   // render space: the map's corner (x, z); y unused
uniform float u_groundFogSize;    // blocks across the map
uniform vec3 u_groundFogBoxMin;   // render space: the box the fog can occupy, low corner
uniform vec3 u_groundFogBoxMax;   // ... and high corner; inside the map, so the map never clamps
uniform float u_groundFogDepth;   // blocks above the ground the fog thins out over
uniform float u_groundFogBelow;   // blocks below the ground it fades over (caves stay clear)
uniform float u_groundFogDensity; // extinction per block in the thick of it, by night
uniform vec3 u_groundFogColor;    // the light the fog scatters toward the eye
uniform float u_groundFogHaze;    // blocks of fog seen every way from the thick of it

const int GROUND_FOG_MAX_STEPS = 20;
const int GROUND_FOG_MIN_STEPS = 6;
// Blocks of ray per step below the cap: a 20-block ray takes the full 20, as all did.
const float GROUND_FOG_STEP_SPAN = 1.0;
// Optical depth past which the march stops: e^-6 lets 0.25% through, under 1/255.
const float GROUND_FOG_OPAQUE = 6.0;

// 0..1: how thick the fog is at p (inside the map: groundFog's box keeps it there).
float groundFogAt(vec3 p) {
    vec2 m = texture(u_groundFogMap, (p.xz - u_groundFogOrigin.xz) / u_groundFogSize).rg;
    float above = p.y - m.g;
    return m.r
        * (1.0 - smoothstep(0.0, u_groundFogDepth, above))
        * smoothstep(-u_groundFogBelow, 0.0, above);
}

// Fog between eye and eye + dir * dist: rgb = in-scattered light, a = transmittance.
vec4 groundFog(vec3 eye, vec3 dir, float dist) {
    if (!u_groundFogEnabled) return vec4(0.0, 0.0, 0.0, 1.0);
    float depth = groundFogAt(eye) * min(dist, u_groundFogHaze);
    // The ray's stretch inside the box (slabs; a zero direction component becomes a
    // tiny one of its sign, so an eye outside that slab misses and one inside spans it).
    vec3 inv = 1.0 / ((vec3(greaterThanEqual(dir, vec3(0.0))) * 2.0 - 1.0) * max(abs(dir), vec3(1e-6)));
    vec3 ta = (u_groundFogBoxMin - eye) * inv;
    vec3 tb = (u_groundFogBoxMax - eye) * inv;
    vec3 tNear = min(ta, tb);
    vec3 tFar = max(ta, tb);
    float t0 = max(0.0, max(tNear.x, max(tNear.y, tNear.z)));
    float t1 = min(dist, min(tFar.x, min(tFar.y, tFar.z)));
    if (t1 > t0) {
        // Interleaved gradient noise: a jitter the eye reads as fine dither, not grain.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        float span = t1 - t0;
        int steps = clamp(int(ceil(span / GROUND_FOG_STEP_SPAN)), GROUND_FOG_MIN_STEPS, GROUND_FOG_MAX_STEPS);
        float opaque = GROUND_FOG_OPAQUE / u_groundFogDensity;
        float layer = 0.0;
        float prev = 0.0;
        for (int i = 1; i <= steps; i++) {
            float u = float(i) / float(steps);
            float next = u * u;
            float mid = mix(prev, next, jitter);
            layer += groundFogAt(eye + dir * (t0 + mid * span)) * (next - prev) * span;
            prev = next;
            if (layer > opaque) break;
        }
        depth = max(depth, layer);
    }
    if (depth <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    float t = exp(-u_groundFogDensity * depth);
    return vec4(u_groundFogColor * (1.0 - t), t);
}

// Fogs a surface `color` at eye + toPoint.
vec3 applyGroundFog(vec3 color, vec3 eye, vec3 toPoint) {
    if (!u_groundFogEnabled) return color;
    float d = length(toPoint);
    vec4 f = groundFog(eye, toPoint / max(d, 1e-4), d);
    return color * f.a + f.rgb;
}
