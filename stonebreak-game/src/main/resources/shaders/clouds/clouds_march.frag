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
//    and cumulonimbus are towers that spread into a flat anvil at the top;
//  - 3D Perlin-Worley noise breaks the body into billows, and finer Worley
//    noise frays its edges: wisps at the base, cauliflower at the top.
// Lighting is a short march toward the sun (Beer-Lambert), a two-lobe
// Henyey-Greenstein phase (the silver lining toward the sun), octaves of
// multiple scattering (why thick cloud is white, not black) and "powder"
// darkening of creases. Light behind the cloud is kept by exp(-sigma * d), the
// rule cloud shadows and water use, so thin cloud is see-through.
//
// Output (half resolution): rgb = cloud light, premultiplied; a = how much of
// the scene behind survives. Composite with blend (ONE, SRC_ALPHA). Second
// output: how far along the ray the cloud a pixel shows sits, which the
// temporal pass reprojects it by.

#include "/shaders/clouds/cloud_field.glsl"

in vec2 vUv;
layout(location = 0) out vec4 fragColor;
// Distance to the pixel's cloud, weighted by how much light each sample adds;
// NO_CLOUD where the ray met none.
layout(location = 1) out float cloudDistance;

uniform sampler2D u_sceneDepth;
uniform sampler3D u_shapeNoise;     // Perlin-Worley, repeats every u_shapeTile blocks
uniform sampler3D u_detailNoise;    // Worley fBm, repeats every u_detailTile blocks
uniform float u_shapeTile;
uniform float u_detailTile;
// How far each noise has risen through the cloud, blocks: clouds slowly boil.
uniform float u_shapeRise;
uniform float u_detailRise;
uniform mat4 u_invViewProj;     // render space
uniform vec3 u_camPos;          // render space
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambientColor;
uniform vec3 u_hazeColor;       // distant clouds fade toward this (the sky)
uniform float u_slabBottom;
uniform float u_slabTop;
uniform float u_fadeStart;      // clouds thin out with distance...
uniform float u_fadeEnd;        // ...and are gone here (inside the map's reach)
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
const int LIGHT_STEPS = 5;
const float LIGHT_STEP0 = 3.0;
const float LIGHT_GROWTH = 1.9;
// Erosion leaves the fringe of a cloud thin; this keeps a cloud as opaque
// overall as its type's sigma says.
const float DENSITY_SCALE = 4.0;
// Highest floor the shape noise may set under the cover. Above 1, so the gaps
// between billows are carved even into a full-cover heart: a cloud wall is a
// cluster of billows, not a sheer plank. The gaps are thin sheets between
// noise cells, so they read as creases, never see-through holes.
const float SHAPE_EROSION = 1.2;
// Cover above the noise's floor that reaches full density: small, so a cloud
// has a crisp, billowed surface instead of a soft fog around a smooth core.
const float EROSION_EDGE = 0.25;
// How much of the eroded body the detail noise may eat.
const float DETAIL_EROSION = 0.5;
// Thinnest a cloud body is drawn, blocks: a column the map only just grows is
// still a sheet of cloud, not a film the noise erodes to nothing.
const float MIN_THICKNESS = 12.0;
// Sheets (stratus, nimbostratus) are a few blocks thick, so seen at a slant a
// ray averages out every billow it crosses and the sheet streaks toward the
// horizon. Their cells are this many times wider than a cumulus billow, as
// real sheet clouds' are.
const float SHEET_STRETCH = 4.0;
// Skylight occlusion: probe distance up, blocks; how strongly the cloud above
// dims it (skylight scatters in from all around, so less than the sun); and
// the most it may darken a point.
const float SKY_PROBE = 5.0;
const float SKY_OCCLUSION = 0.5;
const float SKY_OCCLUSION_MAX = 0.6;
// Depth of a cumulonimbus anvil below the top of the tower, blocks.
const float ANVIL_DEPTH = 30.0;

// The scene is not tone-mapped, so cloud light gets its own exposure curve:
// sunlit tops approach white while the greys of shaded sides and bases survive
// instead of clipping into a flat white sheet.
const float EXPOSURE = 1.6;

const float FLASH_RADIUS = 110.0;
const vec3 FLASH_COLOR = vec3(0.75, 0.8, 1.0);
// Lowest sun elevation the sun march is aimed at, so it never runs flat along the slab.
const float MIN_SUN_Y = 0.15;

float remap(float v, float lo, float hi, float newLo, float newHi) {
    return newLo + (v - lo) / (hi - lo) * (newHi - newLo);
}

// A per-type value (indexed by CloudType ordinal) blended across the type
// boundaries in wetness, so a cloud crossing from one type into the next
// changes optics smoothly instead of in a step. Each type keeps its own
// altitude band while it fades (cloudTypeWeights), so the layers cross-fade
// instead of one sheet sloping into the other (which drew smeared ribbons).
float typeBlend(vec4 v, float wet) {
    return dot(v, cloudTypeWeights(wet));
}

// How much cloud a type allows at height fraction h (0 = base, 1 = top).
float heightProfile(int type, float h) {
    if (h < 0.0 || h > 1.0) return 0.0;
    // A thin sheet, soft on both faces.
    if (type == 1) return smoothstep(0.0, 0.3, h) * (1.0 - smoothstep(0.5, 1.0, h));
    // A thick deck: ragged underside, flat top.
    if (type == 2) return smoothstep(0.0, 0.18, h) * (1.0 - smoothstep(0.75, 1.0, h));
    // Cumulus and storm towers: flat, crisp base where the rising air reaches
    // condensation; domed top.
    return smoothstep(0.0, 0.06, h) * (1.0 - smoothstep(0.4, 1.0, h));
}

struct CloudSample {
    float density;  // 0 = clear air
    float height;   // 0 at the cloud's base, 1 at its top: how much sky it sees
    float sigma;    // extinction per block at density 1
    float albedo;
};

// Mip level that filters a noise volume down to `footprint` blocks. Explicit,
// because a raymarch has no usable screen derivatives: neighbouring pixels sit
// at different steps, so implicit LOD picks a different mip per pixel and the
// distant clouds shimmer.
float noiseLod(sampler3D noise, float tile, float footprint) {
    return log2(max(footprint * float(textureSize(noise, 0).x) / tile, 1.0));
}

// The cloud at render-space p, `footprint` blocks across one pixel there.
// `covered` is false where no cloud is near, so the march may stride.
CloudSample cloudAt(vec3 p, float footprint, bool detail, out bool covered) {
    CloudSample s;
    s.density = 0.0;
    s.height = 0.0;
    // The local weather, pushed by the terrain: cloud piles up on windward
    // faces and funnels around mountains it is too light to cross. The noise
    // is read where the cloud was drawn from, so a steered cloud keeps the
    // billows it had before the mountain bent it.
    float clip;
    vec2 shift;
    CloudColumn c = cloudColumnLocal(p, clip, shift);
    vec2 xz = p.xz + u_cloudOffset + shift;
    float wet = c.wet;
    s.sigma = typeBlend(u_cloudTypeSigma, wet);
    s.albedo = typeBlend(u_cloudTypeAlbedo, wet);

    // Each type the column holds, in its own altitude band, faded by its share.
    // A storm tower's height ramps up from nimbostratus height with wetness
    // (as CloudField.resolve does).
    vec4 h = u_cloudTypeHeight;
    float storm = smoothstep(u_cloudWetSplits.z, u_cloudWetSplits.z + u_cloudTowerRamp, wet);
    vec4 heights = vec4(h.x, h.y, h.z, h.z + (h.w - h.z) * storm);
    vec4 weights = cloudTypeWeights(wet);
    // Where two types' bands overlap, height and sheetness are averaged by
    // each type's share of the cover. Taking them from whichever type covers
    // most flipped them at the altitude where the profiles cross, and skylight
    // (which follows height) drew a hard horizontal line across the cloud.
    float body = 0.0;
    float sum = 0.0;
    float frac = 0.0;
    float sheet = 0.0; // how much of the cover is sheet cloud
    if (c.density > 0.0) {
        for (int t = 0; t < 4; t++) {
            if (weights[t] <= 0.0) continue;
            float base = u_cloudTypeBase[t];
            float f = (p.y - base) / max(heights[t] * c.build, MIN_THICKNESS);
            float b = weights[t] * heightProfile(t, f);
            body = max(body, b);
            sum += b;
            frac += b * clamp(f, 0.0, 1.0);
            sheet += (t == 1 || t == 2) ? b : 0.0;
        }
        body *= c.density * clip;
    }

    // Anvil: a mature storm's updraft hits the top of the troposphere and
    // spreads sideways into a flat sheet wider than the tower beneath it.
    float anvil = 0.0;
    float anvilStorm = smoothstep(u_cloudWetSplits.z - 0.02, u_cloudWetSplits.z + u_cloudTowerRamp, wet);
    if (anvilStorm > 0.0) {
        float a = (u_cloudTypeBase.w + h.w - p.y) / ANVIL_DEPTH; // 0 at the top, 1 at the anvil's base
        anvil = anvilStorm * smoothstep(0.0, 0.2, a) * (1.0 - smoothstep(0.5, 1.0, a));
    }

    float cover = max(body, anvil * clip);
    covered = cover > 0.0;
    if (!covered) return s;
    // The anvil counts as cloud top (it sees the sky), blended in by its share
    // rather than switched on where it overtakes the tower.
    float anvilW = anvil / (anvil + body + 1e-6);
    s.height = mix(frac / max(sum, 1e-6), 1.0, anvilW);
    sheet = sheet / max(sum, 1e-6) * (1.0 - anvilW);

    // Billows: the noise sets the floor the cover must clear (erosion), so the
    // dense heart of a cloud stays solid and only its thinner fringe is carved
    // into separate puffs toward its edges and domes at its top. Scaling the
    // body by the noise instead punched see-through holes into the heart.
    vec3 q = vec3(xz.x, p.y - u_shapeRise, xz.y);
    float shape = textureLod(u_shapeNoise, q / u_shapeTile, noiseLod(u_shapeNoise, u_shapeTile, footprint)).r;
    // Blended, not swapped: switching noise where sheet and cumulus meet cut a
    // hard seam through the cloud.
    if (sheet > 0.0) {
        vec3 qs = vec3(q.x / SHEET_STRETCH, q.y, q.z / SHEET_STRETCH);
        float sheetShape = textureLod(u_shapeNoise, qs / u_shapeTile,
                noiseLod(u_shapeNoise, u_shapeTile * SHEET_STRETCH, footprint)).r;
        shape = mix(shape, sheetShape, sheet);
    }
    float floorAt = (1.0 - shape) * SHAPE_EROSION;
    float d = clamp((cover - floorAt) / EROSION_EDGE, 0.0, 1.0);
    if (detail && d > 0.0) {
        float n = textureLod(u_detailNoise, vec3(xz.x, p.y - u_detailRise, xz.y) / u_detailTile,
                noiseLod(u_detailNoise, u_detailTile, footprint)).r;
        // Wisps (the noise) at the base, billows (its inverse) toward the top.
        n = mix(n, 1.0 - n, clamp(s.height * 3.0, 0.0, 1.0));
        d = clamp(remap(d, n * DETAIL_EROSION, 1.0, 0.0, 1.0), 0.0, 1.0);
    }
    s.density = d;
    return s;
}

// Extinction (sigma * density) at a sample.
float extinction(CloudSample s) {
    return s.sigma * s.density * DENSITY_SCALE;
}

// Optical depth from p toward the sun, through the cloud above and around it.
// How much of the sky a point sees: two short samples straight up. In shade
// (inside a storm, under a deck) skylight is the only light, and without this
// every crease and bulge got the same amount, so the face read as a flat wall.
float skyVisibility(vec3 p, float footprint) {
    bool covered;
    float od = extinction(cloudAt(p + vec3(0.0, SKY_PROBE, 0.0), footprint, false, covered)) * SKY_PROBE
             + extinction(cloudAt(p + vec3(0.0, 3.0 * SKY_PROBE, 0.0), footprint, false, covered)) * 2.0 * SKY_PROBE;
    return exp(-SKY_OCCLUSION * od);
}

float sunOpticalDepth(vec3 p, vec3 dir, float footprint) {
    float od = 0.0;
    float t = 0.0;
    float ds = LIGHT_STEP0;
    for (int i = 0; i < LIGHT_STEPS; i++) {
        vec3 q = p + dir * (t + 0.5 * ds);
        if (q.y > u_slabTop) break;
        bool covered;
        od += extinction(cloudAt(q, max(footprint, ds), i == 0, covered)) * ds;
        t += ds;
        ds *= LIGHT_GROWTH;
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
    float tLimit = u_fadeEnd;
    if (depth < 1.0) tLimit = min(tLimit, length(worldAt(vUv, depth) - ro));

    // Clip to the cloud slab.
    float tNear = 0.0, tFar = u_fadeEnd;
    if (abs(rd.y) < 1e-5) {
        if (ro.y < u_slabBottom || ro.y > u_slabTop) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); cloudDistance = NO_CLOUD; return; }
    } else {
        float ta = (u_slabBottom - ro.y) / rd.y;
        float tb = (u_slabTop - ro.y) / rd.y;
        tNear = max(tNear, min(ta, tb));
        tFar = min(tFar, max(ta, tb));
    }
    if (tNear >= tFar) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); cloudDistance = NO_CLOUD; return; }
    if (tLimit <= tNear) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); cloudDistance = OCCLUDED; return; }
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

    float scale = stepScale(tNear, tFar);
    float t = tNear + scale * stepAt(tNear) * dither(gl_FragCoord.xy);
    float transmittance = 1.0;
    vec3 light = vec3(0.0);
    float distanceSum = 0.0;
    float opacitySum = 0.0;

    // Extra distance the last clear-air stride took beyond a plain step.
    float overshoot = 0.0;
    for (int i = 0; i < MAX_STEPS && t < tFar; i++) {
        float ds = scale * stepAt(t);
        vec3 p = ro + rd * t;
        float footprint = t * pixelAngle;
        bool covered;
        CloudSample s = cloudAt(p, footprint, true, covered);
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
            float od = sunOpticalDepth(p, sunMarch, footprint);
            float sun = phase.x * exp(-od) + 0.5 * phase.y * exp(-0.35 * od) + 0.25 * phase.z * exp(-0.12 * od);
            float powder = mix(1.0, 1.0 - exp(-ext * 16.0), powderAmount);
            // Skylight: the base of a cloud sees less of the sky than its top.
            float skyLit = mix(0.35, 1.0, sqrt(clamp(s.height, 0.0, 1.0)))
                    * mix(1.0 - SKY_OCCLUSION_MAX, 1.0, skyVisibility(p, footprint));
            vec3 lit = s.albedo * (u_sunColor * sun * powder + u_ambientColor * skyLit);
            lit += FLASH_COLOR * flashAt(p);
            lit = 1.0 - exp(-EXPOSURE * lit);

            // Aerial perspective, like the terrain's distance fog...
            lit = mix(lit, u_hazeColor, 0.65 * smoothstep(0.0, u_fadeEnd, t));
            // ...and clouds thin out toward the fade distance instead of ending in a wall.
            float fade = 1.0 - smoothstep(u_fadeStart, u_fadeEnd, t);
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

    fragColor = vec4(light, transmittance);
    cloudDistance = opacitySum > 1e-4 ? distanceSum / opacitySum : NO_CLOUD;
}
