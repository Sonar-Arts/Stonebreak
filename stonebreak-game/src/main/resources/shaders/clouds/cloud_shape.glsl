// The cloud's body: the cloud field's columns (cloud_field.glsl, include it
// first) shaped by each type's vertical profile and carved into billows by 3D
// noise. The cloud march draws it; rain and the rain fog read where its
// underside is, so they hang from the cloud actually drawn rather than from a
// flat altitude. Its uniforms come from CloudUniforms.applyShapeTo.

uniform sampler3D u_shapeNoise;     // Perlin-Worley, repeats every u_shapeTile blocks
uniform sampler3D u_detailNoise;    // Worley fBm, repeats every u_detailTile blocks
uniform float u_shapeTile;
uniform float u_detailTile;
// How far each noise has risen through the cloud, blocks: clouds slowly boil.
uniform float u_shapeRise;
uniform float u_detailRise;

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
// Depth of a cumulonimbus anvil below the top of the tower, blocks.
const float ANVIL_DEPTH = 30.0;

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

// Density from which a cloud reads as its underside: below it, rain and its
// fog hang in clear air.
const float UNDERSIDE_DENSITY = 0.2;
// Blocks between the samples that look for it, before bisection refines them.
const float UNDERSIDE_STEP = 4.0;
const int UNDERSIDE_MAX_STEPS = 40;
const int UNDERSIDE_REFINE = 3;

// Altitude of the underside of the cloud over render-space column xz (`col`,
// from cloudColumnAt there): the lowest point where cloudAt is solid, searched
// up from the lowest band the column holds to `hi`. `hi` where the noise has
// carved the column clear.
float cloudUnderside(vec2 xz, CloudColumn col, float hi, float footprint, bool detail) {
    vec4 weights = cloudTypeWeights(col.wet);
    float lo = hi;
    for (int t = 0; t < 4; t++) {
        if (weights[t] > 0.0) lo = min(lo, u_cloudTypeBase[t]);
    }
    int steps = min(int(ceil((hi - lo) / UNDERSIDE_STEP)), UNDERSIDE_MAX_STEPS);
    bool covered;
    for (int i = 1; i <= steps; i++) {
        float y = min(lo + float(i) * UNDERSIDE_STEP, hi);
        if (cloudAt(vec3(xz.x, y, xz.y), footprint, detail, covered).density < UNDERSIDE_DENSITY) continue;
        float below = y - UNDERSIDE_STEP, above = y;
        for (int k = 0; k < UNDERSIDE_REFINE; k++) {
            float mid = 0.5 * (below + above);
            if (cloudAt(vec3(xz.x, mid, xz.y), footprint, detail, covered).density < UNDERSIDE_DENSITY) below = mid;
            else above = mid;
        }
        return above;
    }
    return hi;
}
