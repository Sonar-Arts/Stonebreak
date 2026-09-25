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
// fade with distance to prevent shimmer/aliasing. Colour and opacity come
// from the water's DENSITY: how much water the eye ray crosses before it
// reaches the opaque scene behind the surface (Beer-Lambert), so thin water is
// light and clear and thick water darkens until the floor is gone. A
// Fresnel-style term still turns grazing angles into a reflective sheet. Culling is disabled by the WaterRenderer, so
// gl_FrontFacing flips the normal for underwater views.
in vec3 vWorldPos;
in vec3 vNormal;
in float vFalling;
in float vSource;
in float vSurfaceHeight;
// Blocks of water between this face and the floor under it, baked per quad.
// Only a fallback for thickness where the scene behind is sky (see main).
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
// Opaque-scene depth, copied by WaterRenderer before the water prepass, plus
// what it takes to turn a sample back into an eye distance.
uniform sampler2D uSceneDepth;
uniform mat4 uInvProjection;
uniform vec4 uViewport; // x, y, width, height

#include "/shaders/lighting/point_lights.glsl"

out vec4 fragColor;

// How fast a river's surface pattern travels, in lattice units per second.
// The ambient drift is 0.06; a reach wants to read as moving without turning
// into a conveyor belt, and this is about four times the drift.
const float RIVER_DRIFT = 0.25;

// Water density, per block of water the eye ray crosses (see main).
// DENSITY drives opacity: 1 - exp(-0.22 * 10) — about 90% of the floor is
// hidden behind 10 blocks of water. ABSORB drives colour, per channel: red
// dies first, then green, so thin water keeps the bright surface blue and
// thick water sinks toward MURK_COLOR.
const float DENSITY = 0.22;
const vec3 ABSORB = vec3(0.30, 0.16, 0.10);
// Opacity of a film of water with nothing behind it to speak of — the very
// top of the water, a shoreline's lip. Low, so shallows stay see-through.
const float SURFACE_ALPHA = 0.25;
// Deep water still reads as WATER, just water you cannot see into: the tint
// only darkens the surface a shade, and it is the opacity that does the
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

    // Density. Looking INTO the water from outside, what hides the floor is
    // how much water the eye looks THROUGH: the distance along this pixel's
    // ray from the surface to the opaque scene behind it (the depth copy taken
    // before any water was drawn). Per pixel, so a sloping bed, a shoreline or
    // a sheer drop-off fades continuously instead of stepping per column, and
    // a grazing look across shallows crosses more water than one straight
    // down. Light that crosses d blocks keeps exp(-k * d) of itself, per
    // channel for the colour and overall for the opacity.
    //
    // Only from outside — the same face seen from below is the surface you
    // look UP through, and how far that stays visible is the underwater fog's
    // job (WorldRenderer), not this one's. The transition is smoothed over a
    // quarter block so a camera bobbing at the waterline doesn't flip between
    // the two readings.
    vec3 V = normalize(uCameraPos - vWorldPos);
    vec2 sceneUv = (gl_FragCoord.xy - uViewport.xy) / uViewport.zw;
    float sceneZ = texture(uSceneDepth, sceneUv).r;
    float thickness;
    if (sceneZ < 1.0) {
        vec4 scene = uInvProjection * vec4(vec3(sceneUv, sceneZ) * 2.0 - 1.0, 1.0);
        thickness = max(length(scene.xyz / scene.w) - dist, 0.0);
    } else {
        // Sky behind the surface (a falling sheet off a cliff, water seen
        // through its side against the horizon): the ray leaves the water
        // somewhere the depth copy cannot see, so estimate from the column.
        thickness = horizontal ? vWaterDepth / max(abs(V.y), 0.02) : vWaterDepth;
    }
    float outside = smoothstep(-0.05, 0.20, uCameraPos.y - vWorldPos.y);
    vec3 transmit = mix(vec3(1.0), exp(-ABSORB * thickness), outside);
    float opacity = (1.0 - exp(-DENSITY * thickness)) * outside;
    // The murk carries the surface pattern too, at the same relative
    // contrast as deep-vs-shallow above: thick water is darker, not flat —
    // tinting to a single colour wiped the ripples off every lake and ocean.
    vec3 murk = MURK_COLOR * mix(0.72, 1.28, pattern);
    baseColor = mix(murk, baseColor, transmit);

    vec3 L = normalize(uSunDirection);

    float ambient = uAmbientLight * 0.55;
    float diffuse = max(dot(N, L), 0.0) * 0.6 * uAmbientLight;
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 64.0) * 0.6 * uAmbientLight * (0.35 + 0.65 * detail);

    // White rivulet highlights on falling sheets.
    float streaks = smoothstep(0.60, 0.85, n) * vFalling * 0.30 * uAmbientLight;

    // Fresnel-style soft-edge transparency on top of the density: grazing
    // angles read as a reflective sheet however thin the water. From outside
    // the floor is the low SURFACE_ALPHA — density supplies the rest; from
    // below it stays the legacy 0.62 so the underside still reads as a
    // bright surface.
    float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    float alpha = mix(mix(0.62, SURFACE_ALPHA, outside), 0.90, fres);
    // Falling columns read better slightly denser; streaks denser still.
    alpha = max(alpha, vFalling * 0.62);
    // Past the 0.92 clamp for thick water: the point of the density is that
    // nothing behind enough of it comes through.
    alpha = max(clamp(alpha + streaks * 0.4, 0.0, 0.92), mix(SURFACE_ALPHA, 1.0, opacity));

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
