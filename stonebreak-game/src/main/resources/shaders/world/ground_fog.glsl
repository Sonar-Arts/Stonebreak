// The Shade's night ground fog (GroundFog.java): a layer u_groundFogDepth blocks deep
// lying on Shade ground, thinning out with height above it. Where the Shade is comes
// from a camera-centred map (ShadeFogMap): R = Shade coverage 0..1, G = ground height.
//
// Beer-Lambert marched across the layer between the eye and the point: the ray is
// clipped to the slab the fog can occupy and to the map, then sampled at jittered
// steps spaced quadratically, densest near the eye where the fog's shape shows.
//
// Standing in the fog, the eye also sees at least u_groundFogHaze blocks of it in
// every direction: the layer alone is a few blocks deep straight up, and the sky
// overhead stayed clear from inside it. The two are not added (that fogged the
// ground twice); the ray takes whichever is deeper.

uniform bool u_groundFogEnabled;
uniform sampler2D u_groundFogMap;
uniform vec3 u_groundFogOrigin;   // render space: the map's corner (x, z); y unused
uniform float u_groundFogSize;    // blocks across the map
uniform float u_groundFogBottom;  // lowest y the fog reaches
uniform float u_groundFogTop;     // highest y the fog reaches
uniform float u_groundFogDepth;   // blocks above the ground the fog thins out over
uniform float u_groundFogBelow;   // blocks below the ground it fades over (caves stay clear)
uniform float u_groundFogDensity; // extinction per block in the thick of it, by night
uniform vec3 u_groundFogColor;    // the light the fog scatters toward the eye
uniform float u_groundFogHaze;    // blocks of fog seen every way from the thick of it

const int GROUND_FOG_STEPS = 20;

// 0..1: how thick the fog is at p.
float groundFogAt(vec3 p) {
    vec2 uv = (p.xz - u_groundFogOrigin.xz) / u_groundFogSize;
    // Fade out over the map's outer eighth, so its edge never shows as a wall.
    vec2 edge = min(uv, 1.0 - uv);
    float rim = smoothstep(0.0, 0.125, min(edge.x, edge.y));
    if (rim <= 0.0) return 0.0;
    vec2 m = texture(u_groundFogMap, uv).rg;
    float above = p.y - m.g;
    return m.r * rim
        * (1.0 - smoothstep(0.0, u_groundFogDepth, above))
        * smoothstep(-u_groundFogBelow, 0.0, above);
}

// Fog between eye and eye + dir * dist: rgb = in-scattered light, a = transmittance.
vec4 groundFog(vec3 eye, vec3 dir, float dist) {
    if (!u_groundFogEnabled) return vec4(0.0, 0.0, 0.0, 1.0);
    float depth = groundFogAt(eye) * min(dist, u_groundFogHaze);
    float t0 = 0.0;
    float t1 = min(dist, u_groundFogSize);
    if (abs(dir.y) > 1e-5) {
        float ta = (u_groundFogBottom - eye.y) / dir.y;
        float tb = (u_groundFogTop - eye.y) / dir.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    } else if (eye.y < u_groundFogBottom || eye.y > u_groundFogTop) {
        t1 = t0;
    }
    if (t1 > t0) {
        // Interleaved gradient noise: a jitter the eye reads as fine dither, not grain.
        float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        float span = t1 - t0;
        float layer = 0.0;
        float prev = 0.0;
        for (int i = 1; i <= GROUND_FOG_STEPS; i++) {
            float u = float(i) / float(GROUND_FOG_STEPS);
            float next = u * u;
            float mid = mix(prev, next, jitter);
            layer += groundFogAt(eye + dir * (t0 + mid * span)) * (next - prev) * span;
            prev = next;
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
