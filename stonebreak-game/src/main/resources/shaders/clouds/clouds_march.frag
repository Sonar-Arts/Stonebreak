#version 330 core

// Volumetric clouds, sculpted after real ones. Each pixel's view ray is marched
// through the cloud slab, and at every step the density is built up in layers:
//  - the cloud map says where clouds are, which type and how far each has
//    grown (cloud_field.glsl), smoothly filtered so nothing follows the grid,
//    under the local weather and pushed by the terrain: cloud piles up on
//    windward faces and funnels around mountains unless it is massive enough
//    to ride over, and reaches down to the ground but never into it;
//  - a vertical profile per type: cumulus have flat bases and domed tops,
//    stratus are thin sheets, nimbostratus thick decks with ragged undersides,
//    and cumulonimbus are towers with near-vertical walls and flattened tops;
//  - 3D Perlin-Worley noise breaks the body into billows, and finer Worley
//    noise frays its edges: wisps at the base, cauliflower at the top.
// Lighting is a short march toward the sun (Beer-Lambert), a two-lobe
// Henyey-Greenstein phase (the silver lining toward the sun), octaves of
// multiple scattering (why thick cloud is white, not black) and "powder"
// darkening of creases. Light behind the cloud is kept by exp(-sigma * d), the
// rule cloud shadows and water use, so thin cloud is see-through.
//
// In the golden hour the warm sunlight is seen mostly toward the sun. Cloud
// droplets scatter forward, so the low, dim, reddened beam lines the clouds
// near the sun in gold, and the clouds opposite send back far less of it. There
// the blue dusk sky lights them instead, and they read pastel pink to grey. The
// change follows the angle from the sun smoothly (sunsetFalloff). Haze and
// skylight follow the sky behind each cloud, gold only where it is gold
// (sunset_fog.glsl).
//
// Above the slab, a high cirrus layer (cirrusLayer): ice streaks combed out
// along the wind, far above the cumulus, for depth.
//
// Output (half resolution): rgb = cloud light, premultiplied; a = how much of
// the scene behind survives. Composite with blend (ONE, SRC_ALPHA). Second
// output: how far along the ray the cloud a pixel shows sits, which the
// temporal pass reprojects it by.

#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shape.glsl"
#include "/shaders/weather/precip_fog.glsl"
#include "/shaders/world/ground_fog.glsl"
#include "/shaders/world/sunset_fog.glsl"

in vec2 vUv;
layout(location = 0) out vec4 fragColor;
// Distance to the pixel's cloud, weighted by how much light each sample adds;
// NO_CLOUD where the ray met none.
layout(location = 1) out float cloudDistance;

uniform sampler2D u_sceneDepth;
uniform mat4 u_invViewProj;     // render space
uniform vec3 u_camPos;          // render space
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambientColor;    // the sky's light on the cloud where the sky is gold...
uniform vec3 u_ambientClear;    // ...and where it is not
uniform vec3 u_hazeColor;       // distant clouds fade toward this (the sky without the gold)
uniform float u_slabBottom;
uniform float u_slabTop;
uniform float u_fadeStart;      // clouds thin out with horizontal distance...
uniform float u_fadeEnd;        // ...and are gone here (the terrain's horizon, inside the map's reach)
// Lightning: up to four live flashes, xyz = render-space light point inside
// the storm cloud, w = intensity (0 = unused). Each lights only the cloud
// within FLASH_RADIUS of it.
uniform vec4 u_flashes[4];
// Frame counter: the step dither moves every frame and the temporal pass
// (clouds_temporal.frag) averages it away.
uniform int u_frame;

const int MAX_STEPS = 256;
const float MIN_TRANSMITTANCE = 0.01;
// cloudDistance where the ray met no cloud (clouds_temporal.frag reads it).
const float NO_CLOUD = -1.0;
// cloudDistance where scene geometry ends the ray before it reaches the slab:
// not sky, so the temporal pass keeps it out of its history clamp.
const float OCCLUDED = -2.0;
// View steps grow with distance, from MIN_STEP blocks up to MAX_STEP...
const float MIN_STEP = 2.0;
const float MAX_STEP = 40.0;
const float STEP_PER_BLOCK = 0.01;
// A ray whose slab crossing needs more steps than MAX_STEPS has them all
// stretched to fit (see stepScale), so it never stops short of the far clouds.
// Inside cloud a step crosses at most this optical depth, so where a pixel's
// dithered first sample lands no longer decides its brightness (that drew the
// dither pattern on dense cloud). Cheap: dense cloud ends a ray in a few steps.
const float MAX_STEP_DEPTH = 0.35;
// ...and stride this much longer through clear sky.
const float EMPTY_STRIDE = 2.0;
// Sun march: LIGHT_STEPS steps, the first LIGHT_STEP0 blocks, each LIGHT_GROWTH longer.
const int LIGHT_STEPS = 4;
const float LIGHT_STEP0 = 3.0;
const float LIGHT_GROWTH = 1.9;
// One more, long sample beyond them (Schneider 2015), so a tall tower shades
// its own lower flanks: the four steps reach only ~40 blocks, this one ~130.
const float LIGHT_FAR_STEP = 90.0;
// Distant cloud (a pixel wider than this many blocks, the threshold dithered
// per pixel so the temporal pass hides it) takes three doubled steps over the
// same reach: its fine self-shadowing is far below a pixel, and the horizon's
// clouds are most of the pixels.
const float LIGHT_LOD_FOOTPRINT = 1.5;
// Once a ray is this opaque, its sun samples skip the detail noise: what is
// left to see is dim (Schneider 2015: twice as fast, no visible change).
const float CHEAP_LIGHT_BELOW = 0.7;
// High cirrus layer (cirrusLayer). Tiles are blocks and divide the map period,
// so the pattern never jumps when u_cloudOffset wraps.
const float CIRRUS_Y = 560.0;
const float CIRRUS_ALONG = 1536.0;  // streak noise tile along x (the wind runs mostly +x)...
const float CIRRUS_ACROSS = 192.0;  // ...and across: fibres ~8x longer than wide
const float CIRRUS_PATCH = 3072.0;  // where the cirrus is: patches ~800 blocks across
const float CIRRUS_CURL = 24.0;     // fibres bend a little
const float CIRRUS_OD = 0.9;        // optical depth at full density: always see-through
const float CIRRUS_ALBEDO = 0.95;
// Skylight occlusion: probe distance up, blocks; how strongly the cloud above
// dims it (skylight scatters in from all around, so less than the sun); and
// the most it may darken a point.
const float SKY_PROBE = 5.0;
const float SKY_OCCLUSION = 0.5;
const float SKY_OCCLUSION_MAX = 0.6;

// The scene is not tone-mapped, so cloud light gets its own exposure curve:
// sunlit tops approach white while the greys of shaded sides and bases survive
// instead of clipping into a flat white sheet.
const float EXPOSURE = 1.6;

const float FLASH_RADIUS = 110.0;
const vec3 FLASH_COLOR = vec3(0.75, 0.8, 1.0);
// Lowest sun elevation the sun march is aimed at, so it never runs flat along the slab.
const float MIN_SUN_Y = 0.15;
// Golden-hour fall-off of the sunlight with the angle from the sun: a gaussian
// in the angle, as wide as SUNSET_LOBE (radians, 70 degrees), over a floor of
// SUNSET_BACK opposite the sun.
const float SUNSET_LOBE = 1.222;
const float SUNSET_BACK = 0.25;
// Sunlight warmth (1 - blue / brightest channel) at which the fall-off is
// complete: SkyLight's GOLDEN_SUN. White noon sun and the blue moon have none.
const float SUNSET_WARMTH = 0.48;

// How much of the sunlight a cloud in view direction cosTheta from the sun shows.
// Full toward the sun, about 0.6 at 90 degrees, SUNSET_BACK opposite; 1 outside
// the golden hour, when the bright beam saturates every cloud to white.
float sunsetFalloff(float cosTheta) {
    float warmth = 1.0 - u_sunColor.b / max(max(u_sunColor.r, u_sunColor.g), max(u_sunColor.b, 1e-4));
    float golden = smoothstep(0.0, SUNSET_WARMTH, warmth);
    float angle = acos(clamp(cosTheta, -1.0, 1.0));
    float lobe = mix(SUNSET_BACK, 1.0, exp(-0.5 * angle * angle / (SUNSET_LOBE * SUNSET_LOBE)));
    return mix(1.0, lobe, golden);
}

// How much of the sky a point at height y over `site` sees: two short samples
// straight up the same column. In shade (inside a storm, under a deck)
// skylight is the only light, and without this every crease and bulge got the
// same amount, so the face read as a flat wall.
float skyVisibility(CloudSite site, float y, float footprint) {
    bool covered;
    // The anvil is left out: right under its overhang every point of a tower
    // wall would lose the same sky and the wall would read as banded.
    float od = extinction(cloudBodyAnvil(site, y + SKY_PROBE, footprint, false, false, covered)) * SKY_PROBE
             + extinction(cloudBodyAnvil(site, y + 3.0 * SKY_PROBE, footprint, false, false, covered)) * 2.0 * SKY_PROBE;
    return exp(-SKY_OCCLUSION * od);
}

// Optical depth from p toward the sun, through the cloud above and around it.
// `detail`: the first step reads the detail noise (it shapes the lit rim).
// Anvil ice counts ANVIL_SHADOW of its depth: thin, forward-scattering, and a
// full-strength shadow under a flat overhang reads as a band across the wall.
float sunOpticalDepth(vec3 p, vec3 dir, float footprint, bool detail, float lodDither) {
    bool coarse = footprint > LIGHT_LOD_FOOTPRINT * (0.75 + 0.5 * lodDither);
    int steps = coarse ? LIGHT_STEPS - 1 : LIGHT_STEPS;
    float growth = coarse ? 2.0 : LIGHT_GROWTH;   // coarse: 6 + 12 + 24 = 42, fine: 3 + 5.7 + 10.8 + 20.6 = 40
    float od = 0.0;
    float t = 0.0;
    float ds = coarse ? 2.0 * LIGHT_STEP0 : LIGHT_STEP0;
    for (int i = 0; i <= steps; i++) {
        if (i == steps) ds = LIGHT_FAR_STEP;
        vec3 q = p + dir * (t + 0.5 * ds);
        if (q.y > u_slabTop) break;
        bool covered;
        CloudSample s = cloudAt(q, max(footprint, ds), detail && i == 0, covered);
        od += extinction(s) * mix(1.0, ANVIL_SHADOW, s.anvil) * ds;
        t += ds;
        ds *= growth;
    }
    return od;
}

// Henyey-Greenstein, scaled so isotropic scattering is 1.
float hg(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5);
}

// Strong forward lobe (the silver lining) plus a weaker back lobe (the
// glow of a cloud with the sun behind the viewer). k < 1 flattens it for
// higher scattering orders.
float cloudPhase(float cosTheta, float k) {
    return mix(hg(cosTheta, -0.25 * k), hg(cosTheta, 0.65 * k), 0.6);
}

// Lightning light at p: each flash lights the cloud near it, not the whole deck.
float flashAt(vec3 p) {
    float sum = 0.0;
    for (int k = 0; k < 4; k++) {
        vec4 f = u_flashes[k];
        if (f.w > 0.0) sum += f.w * (1.0 - smoothstep(0.0, FLASH_RADIUS, distance(p, f.xyz)));
    }
    return sum;
}

// The high cirrus layer: one thin sheet of ice at CIRRUS_Y, far above the
// cumulus, read once per pixel where the ray crosses it. Fibres combed along
// the wind (the shape noise stretched 8:1, two octaves), bent by the curl
// noise, in patches; more of it the cloudier the local weather. Visual only:
// it rains nothing and casts no shadow, so nothing on the CPU mirrors it.
// Returns rgb = light (premultiplied), a = opacity.
vec4 cirrusLayer(vec3 ro, vec3 rd, float tLimit, float across, float pixelAngle,
                 float cosTheta, vec3 sunLight, vec3 ambient, vec3 haze, out float tc) {
    tc = (CIRRUS_Y - ro.y) / rd.y;
    if (rd.y <= 1e-4 || tc <= 0.0 || tc >= tLimit) return vec4(0.0);
    float dist = tc * across;
    float fade = 1.0 - smoothstep(u_fadeStart, u_fadeEnd, dist);
    if (fade <= 0.0) return vec4(0.0);
    vec2 xz = (ro + rd * tc).xz;
    vec2 c = xz + u_cloudOffset;
    // A gentle bend, not a swirl: strong curl combed the fibres into whorls.
    vec2 curl = textureLod(u_curlNoise, c / (u_curlTile * 4.0), 0.0).rg;
    vec2 w = c + curl * CIRRUS_CURL;
    float lod = noiseLod(u_shapeNoise, CIRRUS_ACROSS, tc * pixelAngle);
    // Fibres along x and along the x+z diagonal (a + b and a - b shift by whole
    // tiles when the offset wraps), so they cross and fray instead of lying in
    // one regular set of parallel lines.
    float f1 = textureLod(u_shapeNoise, vec3(w.x / CIRRUS_ALONG, 0.31, w.y / CIRRUS_ACROSS), lod).g;
    vec2 diag = vec2(w.x + w.y, w.x - w.y);
    float f2 = textureLod(u_shapeNoise, vec3(diag.x / (2.0 * CIRRUS_ALONG), 0.67, diag.y / (2.0 * CIRRUS_ACROSS)),
            lod).r;
    float patch = textureLod(u_shapeNoise, vec3(c.x / CIRRUS_PATCH, 0.83, c.y / CIRRUS_PATCH), 0.0).g;
    float tilt = textureLod(u_shapeNoise, vec3(c.x / CIRRUS_PATCH, 0.17, c.y / CIRRUS_PATCH), 0.0).r;
    float bias = cloudWeatherAt(xz).x;
    float cover = smoothstep(0.6 - 0.2 * bias, 0.9 - 0.2 * bias, patch);
    // Which fibre direction a patch combs, so neighbouring patches differ.
    float fibre = mix(f1, f2, smoothstep(0.35, 0.65, tilt));
    float d = cover * smoothstep(0.42, 0.88, fibre);
    if (d <= 0.0) return vec4(0.0);
    float alpha = (1.0 - exp(-CIRRUS_OD * d)) * fade;
    // Ice scatters strongly forward: bright toward the sun, faint away from it.
    vec3 lit = CIRRUS_ALBEDO * (sunLight * cloudPhase(cosTheta, 1.0) * 0.8 + ambient * 1.1);
    lit = 1.0 - exp(-EXPOSURE * lit);
    lit = mix(lit, haze, 0.65 * smoothstep(0.0, u_fadeEnd, dist));
    return vec4(lit * alpha, alpha);
}

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_invViewProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// Interleaved gradient noise: a per-pixel offset for the first step, so step
// banding turns into fine grain. Shifted by the golden ratio every frame, so
// each pixel's offsets fill [0, 1) evenly over the frames the temporal pass
// averages.
float dither(vec2 frag) {
    float ign = fract(52.9829189 * fract(dot(frag, vec2(0.06711056, 0.00583715))));
    return fract(ign + 0.61803399 * float(u_frame & 63));
}

// Farthest scene depth over the 2x2 full-resolution texels under half-res
// pixel `px`. clouds_composite.frag rebuilds it the same way.
float sceneDepthFarthest(ivec2 px) {
    ivec2 last = textureSize(u_sceneDepth, 0) - 1;
    ivec2 p = px * 2;
    return max(max(texelFetch(u_sceneDepth, min(p, last), 0).r,
                   texelFetch(u_sceneDepth, min(p + ivec2(1, 0), last), 0).r),
               max(texelFetch(u_sceneDepth, min(p + ivec2(0, 1), last), 0).r,
                   texelFetch(u_sceneDepth, min(p + ivec2(1, 1), last), 0).r));
}

float stepAt(float t) {
    return clamp(t * STEP_PER_BLOCK, MIN_STEP, MAX_STEP);
}

// Steps stepAt takes from t0 to t1 (no clear-air striding): MIN_STEP up close,
// then growing geometrically, then MAX_STEP.
float stepsNeeded(float t0, float t1) {
    float near = MIN_STEP / STEP_PER_BLOCK, far = MAX_STEP / STEP_PER_BLOCK;
    float n = max(min(t1, near) - t0, 0.0) / MIN_STEP;
    float g0 = max(t0, near), g1 = min(t1, far);
    if (g1 > g0) n += log(g1 / g0) / STEP_PER_BLOCK;
    n += max(t1 - max(t0, far), 0.0) / MAX_STEP;
    return n;
}

// How much to stretch every step so the crossing fits in MAX_STEPS. Without
// it a ray grazing the slab ran out of steps a few hundred blocks out, and the
// clouds beyond popped in along a hard line where rays began to fit.
float stepScale(float t0, float t1) {
    return max(1.0, stepsNeeded(t0, t1) / float(MAX_STEPS - 8));
}

void main() {
    vec3 ro = u_camPos;
    vec3 rd = normalize(worldAt(vUv, 1.0) - ro);
    // Radians one pixel spans: a pixel at distance t covers t * this blocks.
    // Taken here, in uniform control flow, where derivatives are defined.
    float pixelAngle = max(length(dFdx(rd)), length(dFdy(rd)));

    // How far the ray may go: the opaque scene, or the fade-out distance. The
    // scene depth is the FARTHEST of this pixel's 2x2 full-resolution texels: a
    // pixel straddling a block's silhouette then marches the sky behind it, and
    // the depth-aware composite keeps that cloud off the block. Taking one texel
    // let the block's depth stop the ray short, and the cloud-free pixel drew a
    // clear rim of sky around every block edge.
    float depth = sceneDepthFarthest(ivec2(gl_FragCoord.xy));
    // The fade is measured across the ground, as the terrain's distance fog
    // is, so the clouds end over the last terrain drawn: a ray reaches it
    // farther along the ray the steeper it climbs.
    float across = length(rd.xz);
    float tReach = across > 1e-5 ? u_fadeEnd / across : 1e9;
    float tLimit = tReach;
    if (depth < 1.0) tLimit = min(tLimit, length(worldAt(vUv, depth) - ro));

    // Clip to the cloud slab.
    float tNear = 0.0, tFar = tReach;
    bool slab = true;
    if (abs(rd.y) < 1e-5) {
        if (ro.y < u_slabBottom || ro.y > u_slabTop) slab = false;
    } else {
        float ta = (u_slabBottom - ro.y) / rd.y;
        float tb = (u_slabTop - ro.y) / rd.y;
        tNear = max(tNear, min(ta, tb));
        tFar = min(tFar, max(ta, tb));
    }
    if (tNear >= tFar) slab = false;
    bool occluded = slab && tLimit <= tNear;
    if (occluded) slab = false;
    tFar = min(tFar, tLimit);

    vec3 sunMarch = normalize(vec3(u_sunDir.x, max(u_sunDir.y, MIN_SUN_Y), u_sunDir.z));
    float cosTheta = dot(rd, u_sunDir);
    // Multiple scattering (Wrenninge's octaves): each order is weaker, reaches
    // deeper into the cloud and scatters more evenly.
    vec3 phase = vec3(cloudPhase(cosTheta, 1.0), cloudPhase(cosTheta, 0.5), cloudPhase(cosTheta, 0.25));
    // Powder: seen with the sun behind you, a cloud's thin edges and creases
    // have gathered less scattered light than its body, so they read darker.
    // Gone when looking toward the sun.
    float powderAmount = 0.6 * (0.5 - 0.5 * cosTheta);
    vec3 sunLight = u_sunColor * sunsetFalloff(cosTheta);
    // The sky behind the cloud, and the light it casts on it.
    vec3 haze = horizonFogColor(u_hazeColor, rd);
    vec2 flatDir = rd.xz / max(length(rd.xz), 1e-4);
    vec3 ambient = mix(u_ambientClear, u_ambientColor, min(sunsetFogWeight(flatDir), 1.0));

    float scale = slab ? stepScale(tNear, tFar) : 1.0;
    float rayDither = dither(gl_FragCoord.xy);
    float t = tNear + scale * stepAt(tNear) * rayDither;
    float transmittance = 1.0;
    vec3 light = vec3(0.0);
    float distanceSum = 0.0;
    float opacitySum = 0.0;

    // Extra distance the last clear-air stride took beyond a plain step.
    float overshoot = 0.0;
    for (int i = 0; slab && i < MAX_STEPS && t < tFar; i++) {
        float ds = scale * stepAt(t);
        vec3 p = ro + rd * t;
        float footprint = t * pixelAngle;
        bool covered;
        CloudSite site = cloudSiteAtHeight(p.xz, p.y);
        CloudSample s = cloudBodyAt(site, p.y, footprint, true, covered);
        // A stride out of clear air landed in cloud: back up to a plain step
        // past the clear sample, or how deep a pixel enters the cloud depends
        // on its dither and the grain shows through as light.
        if (covered && overshoot > 0.0) {
            t -= overshoot;
            overshoot = 0.0;
            continue;
        }
        if (s.density > 0.0) {
            float ext = extinction(s);
            ds = min(ds, max(MAX_STEP_DEPTH / ext, MIN_STEP * 0.25));
            float od = sunOpticalDepth(p, sunMarch, footprint, transmittance > CHEAP_LIGHT_BELOW, rayDither);
            float sun = phase.x * exp(-od) + 0.5 * phase.y * exp(-0.35 * od) + 0.25 * phase.z * exp(-0.12 * od);
            float powder = mix(1.0, 1.0 - exp(-ext * 16.0), powderAmount);
            // Skylight: the base of a cloud sees less of the sky than its top.
            float skyLit = mix(0.35, 1.0, sqrt(clamp(s.height, 0.0, 1.0)))
                    * mix(1.0 - SKY_OCCLUSION_MAX, 1.0, skyVisibility(site, p.y, footprint));
            vec3 lit = s.albedo * (sunLight * sun * powder + ambient * skyLit);
            lit += FLASH_COLOR * flashAt(p);
            lit = 1.0 - exp(-EXPOSURE * lit);

            // Aerial perspective, like the terrain's distance fog...
            lit = mix(lit, haze, 0.65 * smoothstep(0.0, u_fadeEnd, t * across));
            // ...and clouds thin out toward the fade distance instead of ending in a wall.
            float fade = 1.0 - smoothstep(u_fadeStart, u_fadeEnd, t * across);
            float stepT = exp(-ext * fade * min(ds, tFar - t));
            float added = transmittance * (1.0 - stepT);
            light += added * lit;
            distanceSum += added * t;
            opacitySum += added;
            transmittance *= stepT;
            if (transmittance < MIN_TRANSMITTANCE) break;
        }
        overshoot = covered ? 0.0 : ds * (EMPTY_STRIDE - 1.0);
        t += ds + overshoot;
    }

    // The cirrus, behind the cumulus slab (it hangs far above it).
    if (transmittance >= MIN_TRANSMITTANCE && ro.y < CIRRUS_Y) {
        float tc;
        vec4 ci = cirrusLayer(ro, rd, tLimit, across, pixelAngle, cosTheta, sunLight, ambient, haze, tc);
        if (ci.a > 0.0) {
            float added = transmittance * ci.a;
            light += transmittance * ci.rgb;
            distanceSum += added * tc;
            opacitySum += added;
            transmittance *= 1.0 - ci.a;
        }
    }
    if (transmittance >= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        cloudDistance = occluded ? OCCLUDED : NO_CLOUD;
        return;
    }

    // Rain and snow below the cloud, between it and the eye. The scene behind
    // is already fogged along this whole stretch (terrain itself, the sky by
    // precip_fog_sky.frag), and the composite gives scene * T + light. So the
    // cloud's light is dimmed by the fog in front, and the fog's own light is
    // added here for the share of the scene the cloud hides. Above the clouds
    // there is no fog (WeatherEffects), so the rain stays behind the cloud.
    // The Shade's ground fog the same way, so cloud seen from inside it is fogged
    // like the sky around it (ground_fog_sky.frag).
    if (transmittance < 1.0) {
        vec4 fog = precipFog(ro, rd, tNear);
        light = light * fog.a + fog.rgb * (1.0 - transmittance);
        fog = groundFog(ro, rd, tNear);
        light = light * fog.a + fog.rgb * (1.0 - transmittance);
    }

    fragColor = vec4(light, transmittance);
    cloudDistance = opacitySum > 1e-4 ? distanceSum / opacitySum : NO_CLOUD;
}
