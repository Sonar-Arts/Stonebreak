// The cloud's body: the cloud field's columns (cloud_field.glsl, include it
// first) shaped by each type's vertical profile and carved into billows by 3D
// noise. The cloud march draws it; rain and the rain fog read where its
// underside is, so they hang from the cloud actually drawn rather than from a
// flat altitude. Its uniforms come from CloudUniforms.applyShapeTo.

uniform sampler3D u_shapeNoise;     // Perlin-Worley, R = billows, G = turrets; repeats every u_shapeTile blocks
uniform sampler3D u_detailNoise;    // Worley fBm, repeats every u_detailTile blocks
uniform sampler2D u_curlNoise;      // 2D curl noise (x, z) in [-1, 1], repeats every u_curlTile blocks
uniform float u_shapeTile;
uniform float u_detailTile;
uniform float u_curlTile;
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
// Real cumulus are fractal: a big cloud's lumps are big. A column this tall
// (blocks) starts to mix in the turret channel (twice the billows' size), and
// this tall is built of turrets as far as TURRET_MAX allows; more so toward
// its top, which is where towers bulge.
const float TURRET_H0 = 40.0;
const float TURRET_H1 = 120.0;
const float TURRET_MAX = 0.75;
// Curl noise bends the lookups (blocks at full curl): the body's a little, so
// billows are not laid out on the noise's lattice, the frayed edges' more, so
// wisps swirl like turbulent air instead of pitting in round cells
// (Schneider 2015). The frayed edges at the base swirl most.
const float SHAPE_CURL = 8.0;
const float DETAIL_CURL = 14.0;
// (The curl is read once per column, with the site: per sample it was a
// dependent fetch in front of every noise read, ~8% of the march.)
// Sides, not brims. A column's height grows with the square of its coverage
// while its density is full almost at once, so every cloud had a wide
// full-density brim the minimum thickness tall, with the dome rising slowly
// out of it: seen from the side, a paper-thin sheet curling up into the cloud,
// no wall. Real cumulus have steep, rounded sides. So by the time the rim is
// at full density the column already stands RIM_WALL of its type's height
// (the square root makes the side steep), and the outermost fringe's base is
// lifted (RIM_ROUND of the column, fading in by full density) so the lower
// corner is rounded too, while the base under the cloud stays flat.
const float RIM_WALL = 0.38;
// Build (0..1) at which a storm tower reaches its full height.
const float STORM_PLATEAU = 0.45;
const float RIM_ROUND = 0.3;
float remap(float v, float lo, float hi, float newLo, float newHi) {
    return newLo + (v - lo) / (hi - lo) * (newHi - newLo);
}

// How much cloud a type allows at height fraction h (0 = base, 1 = top).
float heightProfile(int type, float h) {
    if (h < 0.0 || h > 1.0) return 0.0;
    // A thin sheet, soft on both faces.
    if (type == 1) return smoothstep(0.0, 0.3, h) * (1.0 - smoothstep(0.5, 1.0, h));
    // A thick deck: ragged underside, flat top.
    if (type == 2) return smoothstep(0.0, 0.18, h) * (1.0 - smoothstep(0.75, 1.0, h));
    // A storm tower: flat base, near-vertical walls, a flattened top. Tapered
    // from 40% like a cumulus, the erosion ate its upper half and every storm
    // read as a squat heap however tall its column was.
    if (type == 3) return smoothstep(0.0, 0.04, h) * (1.0 - smoothstep(0.7, 1.0, h));
    // Cumulus: flat, crisp base where the rising air reaches condensation;
    // domed top.
    return smoothstep(0.0, 0.06, h) * (1.0 - smoothstep(0.4, 1.0, h));
}

struct CloudSample {
    float density;  // 0 = clear air
    float height;   // 0 at the cloud's base, 1 at its top: how much sky it sees
    float sigma;    // extinction per block at density 1
    float albedo;
    float anvil;    // 0..1: how much of this sample is anvil ice (thin, bright, casts a weak shadow)
};

// Everything about the cloud over one render-space column that does not change
// with height: resolved once, then reused by every sample up that column (the
// skylight probes, the rain's underside search).
struct CloudSite {
    CloudColumn col;
    float ground;   // relief surface, for the terrain clip
    vec2 xz;        // cloud-space position the noise is read at (the terrain may steer it)
    vec2 flow;      // how far the terrain steered the cloud-map lookup (blocks)
    vec2 u;         // render-space position in the unleaned field (the cloud at the lean pivot)
    bool cached;    // read from the site cache (only cached sites have anvils)
    vec2 curl;      // curl noise over the column (x, z), bending the noise lookups
    vec2 anvil;     // anvil amount (0..1) and altitude of its top over the column (cache only)
    vec2 at;        // render-space xz of the sample: the ground the cloud is clipped against is read there
    bool groundRead; // `ground` holds the ground under `at` (otherwise read only once a sample holds cloud)
    vec4 weights;   // each type's share (cloudTypeWeights)
    vec4 heights;   // each type's band height; a storm's ramps up with wetness
    float sigma;
    float albedo;
    float bottom;   // the column's cloud base and top: each type's, weighted by its share
    float top;
};

CloudSite cloudSiteAt(vec2 renderXZ) {
    CloudSite site;
    CloudRelief r = cloudReliefAt(renderXZ);
    vec2 shift;
    site.col = cloudColumnOver(renderXZ, r, shift);
    site.ground = r.height;
    // The noise is read where the cloud was drawn from, so a steered cloud
    // keeps the billows it had before the mountain bent it.
    site.xz = renderXZ + u_cloudOffset + shift;
    site.flow = shift;
    site.u = renderXZ;
    site.at = renderXZ;
    site.groundRead = true;
    site.cached = false;
    site.curl = textureLod(u_curlNoise, site.xz / u_curlTile, 0.0).rg;
    site.anvil = vec2(0.0);
    float wet = site.col.wet;
    // Optics blend across the type boundaries in wetness, so a cloud crossing
    // from one type into the next changes smoothly instead of in a step. Each
    // type keeps its own altitude band while it fades, so the layers
    // cross-fade instead of one sheet sloping into the other (which drew
    // smeared ribbons).
    site.weights = cloudTypeWeights(wet);
    site.sigma = dot(u_cloudTypeSigma, site.weights);
    site.albedo = dot(u_cloudTypeAlbedo, site.weights);
    vec4 h = u_cloudTypeHeight;
    float storm = smoothstep(u_cloudWetSplits.z, u_cloudWetSplits.z + u_cloudTowerRamp, wet);
    site.heights = vec4(h.x, h.y, h.z, h.z + (h.w - h.z) * storm);
    float build = max(site.col.build, RIM_WALL * sqrt(site.col.density));
    // A storm's updraft rises until the tropopause stops it: the tower reaches
    // its full height across most of its breadth (a broad, flat top for the
    // anvil to spread from) instead of peaking over its core like a cumulus
    // dome, which drew storm towers as mountain spires.
    build = mix(build, smoothstep(0.0, STORM_PLATEAU, build), site.weights.w);
    vec4 tops = u_cloudTypeBase + max(site.heights * build, vec4(MIN_THICKNESS));
    site.bottom = dot(site.weights, u_cloudTypeBase);
    site.top = max(dot(site.weights, tops), site.bottom + MIN_THICKNESS);
    float fringe = 1.0 - site.col.density;
    site.bottom += RIM_ROUND * fringe * fringe * (site.top - site.bottom);
    return site;
}

// The site cache (CloudSiteCache): cloudSiteAt evaluated once per frame on
// two render-space grids around the camera, side by side in one texture - near
// (fine texels) and far (coarse, out to the fade distance). The march reads a
// site with one bilinear tap per texture instead of the full column lookup
// (relief, weather, bicubic cloud map, terrain flow: 7 to 11 taps), at every
// view step and every sun step. Positions are the unleaned field's (the
// cloud at the lean pivot); outside both grids the exact path is taken.
uniform bool u_siteCacheOn;
uniform sampler2D u_siteCacheA;     // bottom, top, density, wetness
uniform sampler2D u_siteCacheB;     // flow shift xz, anvil mass, anvil mass * top (mipmapped)
uniform sampler2D u_siteCacheC;     // anvil amount, anvil top, curl xz (built from B's mips)
uniform vec4 u_siteCacheOrigin;     // xy = near grid's (0, 0) corner, zw = far grid's (render space)
uniform vec2 u_siteCacheSpan;       // blocks per side: near, far
uniform float u_siteCacheTexels;    // texels per grid side
// Share of the near grid, in from its edge, over which it fades into the far one.
const float SITE_CACHE_BLEND = 0.05;

// The site from cached values at unleaned position u.
CloudSite cloudSiteFromCache(vec2 u, vec4 a, vec2 flow, vec4 c) {
    CloudSite site;
    site.col.density = a.z;
    site.col.wet = a.w;
    site.col.base = a.x;
    site.col.top = a.y;
    site.col.reach = a.y;
    site.col.build = 1.0;
    site.col.sigma = 0.0;
    site.col.albedo = 0.0;
    site.col.type = cloudTypeOf(a.w);
    site.ground = 0.0;
    site.xz = u + u_cloudOffset + flow;
    site.flow = flow;
    site.u = u;
    site.at = u;
    site.groundRead = false;
    site.cached = true;
    site.anvil = c.xy;
    site.curl = c.zw;
    site.weights = cloudTypeWeights(a.w);
    site.sigma = dot(u_cloudTypeSigma, site.weights);
    site.albedo = dot(u_cloudTypeAlbedo, site.weights);
    site.heights = vec4(0.0);
    site.bottom = a.x;
    site.top = a.y;
    return site;
}

// Reads the cache at unleaned position u; false outside both grids.
bool cloudSiteCached(vec2 u, out vec4 a, out vec2 flow, out vec4 c) {
    float h = 0.5 / u_siteCacheTexels;
    vec2 fF = (u - u_siteCacheOrigin.zw) / u_siteCacheSpan.y;
    float eF = min(min(fF.x, fF.y), min(1.0 - fF.x, 1.0 - fF.y));
    if (eF < h) return false;
    vec2 fN = (u - u_siteCacheOrigin.xy) / u_siteCacheSpan.x;
    float eN = min(min(fN.x, fN.y), min(1.0 - fN.x, 1.0 - fN.y));
    vec2 uvF = vec2(0.5 + 0.5 * clamp(fF.x, h, 1.0 - h), clamp(fF.y, h, 1.0 - h));
    vec2 uv = uvF;
    float w = 0.0;
    if (eN >= h) {
        uv = vec2(0.5 * clamp(fN.x, h, 1.0 - h), clamp(fN.y, h, 1.0 - h));
        w = smoothstep(h, SITE_CACHE_BLEND, eN);
    }
    a = textureLod(u_siteCacheA, uv, 0.0);
    c = textureLod(u_siteCacheC, uv, 0.0);
    if (w > 0.0 && w < 1.0) {
        a = mix(textureLod(u_siteCacheA, uvF, 0.0), a, w);
        c = mix(textureLod(u_siteCacheC, uvF, 0.0), c, w);
    }
    // The flow shift only matters where there is cloud to carve: clear columns
    // (most of a march) skip the read.
    flow = vec2(0.0);
    if (a.z > 0.0 || c.x > 0.0) {
        flow = textureLod(u_siteCacheB, uv, 0.0).rg;
        if (w > 0.0 && w < 1.0) flow = mix(textureLod(u_siteCacheB, uvF, 0.0).rg, flow, w);
    }
    return true;
}

// The site of the cloud at height y over render-space column xz: the field is
// leaned downwind with height (u_cloudLean), so the column is looked up where
// the cloud at that height came from; the ground the cloud is clipped against
// is the one actually under xz.
CloudSite cloudSiteAtHeight(vec2 xz, float y) {
    vec2 u = xz - cloudLeanAt(y);
    CloudSite site;
    vec4 a, c;
    vec2 flow;
    if (u_siteCacheOn && cloudSiteCached(u, a, flow, c)) {
        site = cloudSiteFromCache(u, a, flow, c);
    } else {
        site = cloudSiteAt(u);
    }
    site.at = xz;
    site.groundRead = false;
    return site;
}

// Anvils. A mature storm's updraft spreads out where it stops rising, and the
// upper wind drags the spread downwind: a flat-topped overhang at the storm's
// own top. Built from the storms themselves, not as a layer: the site cache
// holds each column's storm mass and mass * top (u_siteCacheB.zw), its mip
// pyramid blurs them out ANVIL_LEVEL texels (about 50-60 blocks) and the read
// is shifted upwind, so the anvil reaches out past the tower, farthest on the
// downwind side, at the mass-weighted top of the storms under it. (The old
// anvil was one sheet at a fixed altitude for every storm: it read as a level
// band across every storm wall seen from the side.) It is ice: thin
// (ANVIL_SIGMA), bright, its shadow weakened in the sun march (ANVIL_SHADOW)
// and left out of the skylight probe, so it never stamps a dark stripe across
// the wall under it.
uniform vec2 u_cloudWindDir;        // normalized wind direction (render-space XZ)
const float ANVIL_LOWEST = 196.0;   // no anvil below this altitude: skip its taps
const float ANVIL_DRIFT = 56.0;     // blocks the overhang is dragged downwind
const float ANVIL_THICK = 30.0;     // over a storm's heart...
const float ANVIL_MIN_THICK = 12.0; // ...and at the overhang's edge
const float ANVIL_SIGMA = 0.05;
const float ANVIL_ALBEDO = 0.9;
const float ANVIL_SHADOW = 0.3;
const float ANVIL_EROSION = 0.55;
const int ANVIL_LEVEL_NEAR = 4;     // 16 texels x 4 blocks
const int ANVIL_LEVEL_FAR = 2;      // 4 texels x 12 blocks

// Anvil mass and mass * top at cascade-local f, smoothly (cubic B-spline over
// the mip level, as cloudMapCubic: box mips read bilinearly looked square).
vec2 cloudAnvilTap(vec2 f, float x0, int level) {
    float n = u_siteCacheTexels / float(1 << level);
    vec2 p = f * n - 0.5;
    vec2 i = floor(p);
    vec2 fr = p - i;
    vec2 f2 = fr * fr, f3 = f2 * fr;
    vec2 w0 = (1.0 - 3.0 * fr + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * fr + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 g0 = w0 + w1, g1 = w2 + w3;
    float lo = 0.5 / n, hi = 1.0 - 0.5 / n;
    vec2 h0 = clamp((i - 0.5 + w1 / g0) / n, lo, hi);
    vec2 h1 = clamp((i + 1.5 + w3 / g1) / n, lo, hi);
    float lod = float(level);
    vec2 a = textureLod(u_siteCacheB, vec2(x0 + 0.5 * h0.x, h0.y), lod).zw;
    vec2 b = textureLod(u_siteCacheB, vec2(x0 + 0.5 * h1.x, h0.y), lod).zw;
    vec2 c = textureLod(u_siteCacheB, vec2(x0 + 0.5 * h0.x, h1.y), lod).zw;
    vec2 d = textureLod(u_siteCacheB, vec2(x0 + 0.5 * h1.x, h1.y), lod).zw;
    return g0.y * (g0.x * a + g1.x * b) + g1.y * (g0.x * c + g1.x * d);
}

// The anvil over unleaned position u: x = amount (0..1), y = its top. For the
// cache's C texture (cloud_site_cache_c.frag), once per texel per frame.
vec2 cloudAnvilField(vec2 u) {
    vec2 p = u - u_cloudWindDir * ANVIL_DRIFT;
    vec2 fF = (p - u_siteCacheOrigin.zw) / u_siteCacheSpan.y;
    if (min(min(fF.x, fF.y), min(1.0 - fF.x, 1.0 - fF.y)) < 0.01) return vec2(0.0);
    vec2 fN = (p - u_siteCacheOrigin.xy) / u_siteCacheSpan.x;
    vec2 m = min(min(fN.x, fN.y), min(1.0 - fN.x, 1.0 - fN.y)) > SITE_CACHE_BLEND
            ? cloudAnvilTap(fN, 0.0, ANVIL_LEVEL_NEAR)
            : cloudAnvilTap(fF, 0.5, ANVIL_LEVEL_FAR);
    float a = smoothstep(0.06, 0.4, m.x);
    return vec2(a, a > 0.0 ? m.y / max(m.x, 1e-3) : 0.0);
}

// How much anvil the cloud at height y over `site` holds, 0..1 (before erosion).
float cloudAnvilBody(CloudSite site, float y) {
    float a = site.anvil.x;
    if (a <= 0.0 || y < ANVIL_LOWEST) return 0.0;
    float thick = mix(ANVIL_MIN_THICK, ANVIL_THICK, a);
    float h = (y - (site.anvil.y - thick)) / thick;
    return a * smoothstep(0.0, 0.4, h) * (1.0 - smoothstep(0.7, 1.0, h));
}

// Mip level that filters a noise volume down to `footprint` blocks. Explicit,
// because a raymarch has no usable screen derivatives: neighbouring pixels sit
// at different steps, so implicit LOD picks a different mip per pixel and the
// distant clouds shimmer.
float noiseLod(sampler3D noise, float tile, float footprint) {
    return log2(max(footprint * float(textureSize(noise, 0).x) / tile, 1.0));
}

// The cloud at height y over `site`, `footprint` blocks across one pixel there.
// `covered` is false where no cloud is near, so the march may stride.
// `withAnvil` false leaves the anvil out (the skylight probe: see ANVIL_SHADOW).
CloudSample cloudBodyAnvil(CloudSite site, float y, float footprint, bool detail, bool withAnvil, out bool covered) {
    CloudSample s;
    s.density = 0.0;
    s.height = 0.0;
    s.sigma = site.sigma;
    s.albedo = site.albedo;
    s.anvil = 0.0;
    CloudColumn c = site.col;

    // One cloud per column: its base, top and vertical profile are each type's,
    // blended by the type's share, and the profile is read at one height
    // fraction through that blended extent. Drawing each type in its own fixed
    // altitude band (and taking the max) stepped the cover down wherever one
    // band ended inside another (nimbostratus tops at 208 inside a storm
    // tower), and the erosion carved those steps into level shelves at the
    // same altitudes on every mixed cloud. Blended, a sheet thickens smoothly
    // into a heap across a type boundary instead.
    float through = (y - site.bottom) / (site.top - site.bottom);
    float body = 0.0;
    float sheet = site.weights.y + site.weights.z; // how much of the cover is sheet cloud
    if (c.density > 0.0) {
        for (int t = 0; t < 4; t++) {
            if (site.weights[t] > 0.0) body += site.weights[t] * heightProfile(t, through);
        }
        body *= c.density;
    }

    // The anvil merges into the tower's top; where it reaches past the tower,
    // it is ice (see cloudAnvilBody).
    float anvil = withAnvil ? cloudAnvilBody(site, y) : 0.0;
    // The ground clip, read only now that there is cloud here.
    if (body > 0.0 || anvil > 0.0) {
        float ground = site.groundRead ? site.ground : cloudGroundAt(site.at);
        float clip = smoothstep(ground - u_flowClipSink, ground, y);
        body *= clip;
        anvil *= clip;
    }
    if (anvil > body) {
        s.anvil = clamp((anvil - body) * 4.0, 0.0, 1.0);
        s.sigma = mix(s.sigma, ANVIL_SIGMA, s.anvil);
        s.albedo = mix(s.albedo, ANVIL_ALBEDO, s.anvil);
        sheet = max(sheet, s.anvil);   // stretched noise: fibrous ice
        body = anvil;
    }
    covered = body > 0.0;
    if (!covered) return s;
    s.height = clamp(through, 0.0, 1.0);

    // Billows: the noise sets the floor the cover must clear (erosion), so the
    // dense heart of a cloud stays solid and only its thinner fringe is carved
    // into separate puffs toward its edges and domes at its top. Scaling the
    // body by the noise instead punched see-through holes into the heart.
    vec2 curl = site.curl;
    vec3 q = vec3(site.xz.x, y - u_shapeRise, site.xz.y) + vec3(curl.x, 0.0, curl.y) * SHAPE_CURL;
    float shape = 0.5;
    // Blended, not swapped: switching noise where sheet and cumulus meet cut a
    // hard seam through the cloud.
    if (sheet < 1.0) {
        vec2 rg = textureLod(u_shapeNoise, q / u_shapeTile, noiseLod(u_shapeNoise, u_shapeTile, footprint)).rg;
        // Billows, turrets mixed in with the column's height. Two independent
        // noises mixed average toward 0.5, which the erosion would read as a
        // smoother cloud: the spread is restored.
        float turret = TURRET_MAX * smoothstep(TURRET_H0, TURRET_H1, site.top - site.bottom)
                * mix(0.6, 1.0, s.height);
        float spread = inversesqrt((1.0 - turret) * (1.0 - turret) + turret * turret);
        shape = clamp(0.5 + (mix(rg.r, rg.g, turret) - 0.5) * spread, 0.0, 1.0);
    }
    if (sheet > 0.0) {
        vec3 qs = vec3(q.x / SHEET_STRETCH, q.y, q.z / SHEET_STRETCH);
        float sheetShape = textureLod(u_shapeNoise, qs / u_shapeTile,
                noiseLod(u_shapeNoise, u_shapeTile * SHEET_STRETCH, footprint)).r;
        shape = mix(shape, sheetShape, sheet);
    }
    // Anvil ice is a sheet some 30 blocks thick: full erosion punched holes
    // through it, so it is carved more gently (only its edges fray).
    float floorAt = (1.0 - shape) * SHAPE_EROSION * mix(1.0, ANVIL_EROSION, s.anvil);
    float d = clamp((body - floorAt) / EROSION_EDGE, 0.0, 1.0);
    if (detail && d > 0.0) {
        vec3 dq = vec3(site.xz.x, y - u_detailRise, site.xz.y)
                + vec3(curl.x, 0.0, curl.y) * (DETAIL_CURL * (1.0 - 0.7 * s.height));
        float n = textureLod(u_detailNoise, dq / u_detailTile, noiseLod(u_detailNoise, u_detailTile, footprint)).r;
        // Wisps (the noise) at the base, billows (its inverse) toward the top.
        n = mix(n, 1.0 - n, clamp(s.height * 3.0, 0.0, 1.0));
        d = clamp(remap(d, n * DETAIL_EROSION, 1.0, 0.0, 1.0), 0.0, 1.0);
    }
    s.density = d;
    return s;
}

CloudSample cloudBodyAt(CloudSite site, float y, float footprint, bool detail, out bool covered) {
    return cloudBodyAnvil(site, y, footprint, detail, true, covered);
}

// The cloud at render-space p (cloudSiteAtHeight + cloudBodyAt).
CloudSample cloudAt(vec3 p, float footprint, bool detail, out bool covered) {
    return cloudBodyAt(cloudSiteAtHeight(p.xz, p.y), p.y, footprint, detail, covered);
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
// cloudUnderside where no cloud is drawn over the column.
const float NO_UNDERSIDE = -1.0;

// Altitude of the underside of the cloud over `site`: the lowest point where
// it is drawn solid, searched up through the column. NO_UNDERSIDE where the
// noise carved the column clear, as it does at the thin fringe of a cloud:
// nothing is drawn there to fall from. (Falling back to the column's top made
// rain stream out of clear sky beside every cloud, and down through the body
// of the cloud above.)
float cloudUnderside(CloudSite site, float footprint, bool detail) {
    float lo = site.bottom, hi = site.top;
    if (site.col.density <= 0.0) return NO_UNDERSIDE;
    int steps = min(int(ceil((hi - lo) / UNDERSIDE_STEP)), UNDERSIDE_MAX_STEPS);
    bool covered;
    for (int i = 0; i <= steps; i++) {
        float y = min(lo + float(i) * UNDERSIDE_STEP, hi);
        if (cloudBodyAt(site, y, footprint, detail, covered).density < UNDERSIDE_DENSITY) continue;
        if (i == 0) return y;
        float below = y - UNDERSIDE_STEP, above = y;
        for (int k = 0; k < UNDERSIDE_REFINE; k++) {
            float mid = 0.5 * (below + above);
            if (cloudBodyAt(site, mid, footprint, detail, covered).density < UNDERSIDE_DENSITY) below = mid;
            else above = mid;
        }
        return above;
    }
    return NO_UNDERSIDE;
}
