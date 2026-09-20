#version 330 core
// Dedicated water shader — fragment stage.
//
// Fully procedural surface (domain-warped value-noise fbm): no texture binds,
// no CPU tile regeneration. Top/bottom faces sample the pattern in world XZ so
// it is seamless across faces and chunks; side faces sample world-space
// coordinates along the face plane (U = horizontal world axis, V = world Y),
// so wide waterfalls are seamless across neighboring columns — the pattern
// scrolls downward, fast and vertically streaked on falling sheets, gentle on
// ordinary flowing sides. Fine detail, normal perturbation and specular all
// fade with distance to prevent shimmer/aliasing. Fresnel-style view-angle
// alpha: looking straight down is see-through, grazing angles read as a
// reflective sheet. Culling is disabled by the WaterRenderer, so
// gl_FrontFacing flips the normal for underwater views.
in vec3 vWorldPos;
in vec3 vNormal;
in float vFalling;
in float vSource;
in float vSurfaceHeight;
// Blocks of water between this face and the floor under it, baked per quad.
flat in float vWaterDepth;
// 0 = still water; 1..8 = a river running in octant 0..7. See water.vert.
flat in float vFlow;

uniform vec3 uSunDirection;
uniform float uAmbientLight;
uniform vec3 uCameraPos;
// World XZ of the render origin — vWorldPos is render-space, and the
// procedural lattices below are world-space functions.
uniform vec2 uRenderOrigin;
uniform float uTime;
uniform bool uWavesEnabled;
// Atmospheric distance fog — same parameters the world shader gets, so far
// diagonal water fades into the sky in step with the terrain around it.
// uFogEnd <= uFogStart disables.
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
// Sphere rather than cylinder — see u_fogSpherical in world.frag. Kept in step
// with the world shader so a water surface and the terrain behind it fade
// together whichever way the eye is pointing.
uniform bool uFogSpherical;
// FastLOD crossfade opacity (1.0 = solid). FastLOD water sheets render
// through this same shader; while their node dissolves in/out this drives a
// screen-door dither matching the world shader's terrain fade, so a node's
// seabed and its water sheet fade together.
uniform float uLodFade;

#include "/shaders/lighting/point_lights.glsl"

out vec4 fragColor;

// How fast a river's surface pattern travels, in lattice units per second.
// The ambient drift is 0.06; a reach wants to read as moving without turning
// into a conveyor belt, and this is about four times the drift.
const float RIVER_DRIFT = 0.25;

// Column depth (blocks) at which the floor below the water is fully hidden
// from outside, and the colour a column that deep reads as. Shallows stay
// see-through, so a reef or a stream bed is still readable from the bank.
const float MURK_FULL_DEPTH = 12.0;
// Deep water still reads as WATER, just water you cannot see into: the tint
// only darkens the surface a shade, and it is the opacity below that does the
// actual hiding. Mixing all the way to an abyss colour turns an ocean into a
// flat black hole in the world.
const vec3 MURK_COLOR = vec3(0.10, 0.30, 0.54);

// 4x4 Bayer thresholds for the LOD crossfade dither (same table as the world
// shader so terrain and water dissolve with an identical pattern).
const float LOD_BAYER[16] = float[16](
     0.0,  8.0,  2.0, 10.0,
    12.0,  4.0, 14.0,  6.0,
     3.0, 11.0,  1.0,  9.0,
    15.0,  7.0, 13.0,  5.0);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    return vnoise(p) * 0.65 + vnoise(p * 2.13 + vec2(17.7, 9.2)) * 0.35;
}

void main() {
    // FastLOD crossfade: per-pixel screen-door discard, applied in both the
    // depth prepass and the color pass so their coverage matches exactly.
    if (uLodFade < 1.0) {
        int di = (int(gl_FragCoord.y) & 3) * 4 + (int(gl_FragCoord.x) & 3);
        if (uLodFade < (LOD_BAYER[di] + 0.5) / 16.0) discard;
    }

    float t = uWavesEnabled ? uTime : 0.0;
    bool horizontal = abs(vNormal.y) > 0.5;

    // The pattern domains below are world-space functions and have to stay
    // anchored to the world, or the whole surface pattern would slide every
    // time the render origin steps. Everything else here (distance fades,
    // view vectors, point lights) is a difference against another render-space
    // quantity and uses vWorldPos directly.
    vec3 absPos = vWorldPos + vec3(uRenderOrigin.x, 0.0, uRenderOrigin.y);

    // Pattern domain per face kind.
    vec2 p;
    if (horizontal) {
        // Top/bottom: world-space XZ with a slow directional drift.
        //
        // A river's surface drifts DOWNSTREAM instead, and faster: the whole
        // point of the flow code is that a reach does not read like the ocean
        // it eventually joins. The lattice itself stays anchored to the world
        // (only the offset moves), so neighbouring cells of the same reach
        // still share one unbroken pattern, and the direction is flat-shaded
        // so a confluence steps between directions instead of smearing them.
        vec2 drift = vec2(t * 0.060, t * 0.045);
        if (vFlow > 0.5) {
            float a = (vFlow - 1.0) * 0.78539816;  // octant -> radians
            drift = -vec2(cos(a), sin(a)) * t * RIVER_DRIFT;
        }
        p = absPos.xz * 0.35 + drift;
    } else {
        // Sides: world-space face coords — U runs along the face plane,
        // V is world height. Continuous across adjacent blocks, so a wide
        // waterfall shows one unbroken pattern instead of per-column tiles.
        // Sampling V + t makes features flow DOWNWARD.
        float u = abs(vNormal.x) > 0.5 ? absPos.z : absPos.x;
        float scroll = vFalling > 0.5 ? 2.2 : 0.35;
        vec2 q = vec2(u, absPos.y + t * scroll);
        if (vFalling > 0.5) {
            // Stretch the lattice vertically into falling rivulet streaks.
            q = vec2(q.x * 2.4, q.y * 0.55);
        }
        p = q * 1.5;
    }

    // Fine detail aliases into shimmer at distance — fade it out.
    float dist = length(uCameraPos - vWorldPos);
    float detail = clamp(1.0 - dist / 72.0, 0.0, 1.0);

    // Domain warp: bends the noise lattice so the pattern reads as ripples
    // and rivulets rather than grid-aligned blobs. The warp doubles as the
    // surface slope for normal perturbation (no extra noise taps).
    vec2 warp = vec2(fbm(p + vec2(0.0, 3.7)), fbm(p + vec2(5.2, 1.3))) - 0.5;
    float n = fbm(p + warp * 0.9);
    float n2 = fbm(p * 2.3 - warp * 0.6 + vec2(3.1, -2.4) + t * 0.05);
    float pattern = clamp(mix(n, n * 0.68 + n2 * 0.32, detail), 0.0, 1.0);

    vec3 N = normalize(vNormal + vec3(warp.x, 0.0, warp.y) * (0.15 + 0.75 * detail));
    if (!gl_FrontFacing) {
        N = -N;
    }

    // Tuned to the legacy renderer's bright sky-blue look (light ~RGB
    // 100-140,175-200,225-235 on screen) once the ambient+diffuse lighting
    // factor (~1.15 at noon) is applied.
    vec3 deep = vec3(0.24, 0.50, 0.78);
    vec3 shallow = vec3(0.42, 0.68, 0.92);
    vec3 baseColor = mix(deep, shallow, pattern);

    // Depth murk. Looking INTO the water from outside, the floor disappears
    // the deeper it lies: the surface tints toward MURK_COLOR and stops
    // letting the seabed through over the first MURK_FULL_DEPTH blocks of
    // column depth. Only from outside — the same face seen from below is the
    // surface you look UP through, and how far that stays visible is the
    // underwater fog's job (WorldRenderer), not this one's. The transition is
    // smoothed over a quarter block so a camera bobbing at the waterline
    // doesn't flip between the two readings.
    float outside = smoothstep(-0.05, 0.20, uCameraPos.y - vWorldPos.y);
    float murk = smoothstep(0.0, MURK_FULL_DEPTH, vWaterDepth) * outside;
    baseColor = mix(baseColor, MURK_COLOR, murk * 0.7);

    vec3 L = normalize(uSunDirection);
    vec3 V = normalize(uCameraPos - vWorldPos);

    float ambient = uAmbientLight * 0.55;
    float diffuse = max(dot(N, L), 0.0) * 0.6 * uAmbientLight;
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 64.0) * 0.6 * uAmbientLight * (0.35 + 0.65 * detail);

    // White rivulet highlights on falling sheets.
    float streaks = smoothstep(0.60, 0.85, n) * vFalling * 0.30 * uAmbientLight;

    // Fresnel-style soft-edge transparency. Higher floor keeps the water
    // reading as a bright surface (legacy look) instead of tinted terrain.
    float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    float alpha = mix(0.62, 0.90, fres);
    // Falling columns read better slightly denser; streaks denser still.
    alpha = max(alpha, vFalling * 0.62);
    alpha = clamp(alpha + streaks * 0.4, 0.0, 0.92);
    // ...and past the Fresnel clamp for deep water: the point of the murk is
    // that nothing behind it comes through.
    alpha = mix(alpha, 1.0, murk);

    vec3 color = baseColor * (ambient + diffuse) + vec3(1.0) * spec + vec3(streaks);
    // Torchlight on the water surface.
    color = applyPointLight(color, baseColor,
            pointLightContribution(vWorldPos, N) * pointLightWeight(uAmbientLight, 1.0));

    // Distance fog toward the sky color, measured exactly as the world shader
    // measures it — alpha untouched so the blend over terrain stays correct.
    // Native water and FastLOD sheets share this shader, so the near/far
    // water handover needs no color matching: both sides ARE the same code
    // blending over real geometry (native seabed vs LOD seabed).
    if (uFogEnd > uFogStart) {
        float fogDist = uFogSpherical
                ? length(vWorldPos - uCameraPos)
                : length(vWorldPos.xz - uCameraPos.xz);
        float fogF = smoothstep(uFogStart, uFogEnd, fogDist);
        color = mix(color, uFogColor, fogF);
    }

    fragColor = vec4(color, alpha);
}
