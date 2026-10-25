// Cloud field on the GPU: the raw noise map plus the rule that resolves it
// into columns under the local weather, and the terrain that bends it. Shared
// by the cloud raymarch and every receiver of cloud shadows. The rules mirror
// CloudField.resolve, CloudType.forWetness and TerrainFlow in Java — change
// them together. Every number they use arrives as a uniform from
// CloudUniforms, so the tuning lives only in Java.

// RGBA8, GL_REPEAT: R = cumulus, G = wetness, B = detail, A = organisation (see CloudField.raw).
// Texel (i, j) holds cloud-space cell (i, j) modulo the map size.
uniform sampler2D u_cloudMap;
// Render-space XZ + this = cloud-space XZ, wrapped to the map period so it
// stays small enough for float precision.
uniform vec2 u_cloudOffset;
uniform float u_cloudCell;          // blocks per cell
uniform float u_cloudVoxel;         // vertical quantum, blocks
uniform float u_cloudThreshold;     // coverage noise where cloud starts at zero coverage bias
uniform float u_cloudBiasSpan;      // how far one unit of coverage bias moves it
uniform float u_cloudEdge;          // noise range from wisp to full density
uniform float u_cloudDomeRamp;      // noise range from flat to full height
uniform float u_cloudOrganisation;  // share of the organisation (the A channel) in unorganised coverage
// Organised systems (CloudField.organised): x = organisation share when fully
// organised, y/z = organisation channel from scattered to organised, w = how
// far a fair-weather system organises.
uniform vec4 u_cloudOrganised;
uniform vec2 u_cloudOrganisedLift;  // coverage, wetness an organised storm gains
uniform float u_cloudCellWetness;   // share of the cumulus in a scattered system's wetness
uniform float u_cloudTowerRamp;     // wetness range from storm floor to full tower
uniform vec3 u_cloudWetSplits;      // stratus max, nimbostratus min, cumulonimbus min
// Per type, indexed by CloudType ordinal.
uniform vec4 u_cloudTypeBase;
uniform vec4 u_cloudTypeHeight;
uniform vec4 u_cloudTypeSigma;
uniform vec4 u_cloudTypeAlbedo;
uniform vec4 u_cloudTypeRain;       // rain or snow a full, grown column drops
uniform float u_cloudTypeBlend;     // wetness range over which one type fades into the next
uniform float u_cloudSmallRain;     // share of that the smallest cloud drops

// Local weather (WeatherMap): RG = coverage, wetness bias. World-anchored
// texels, GL_REPEAT, bilinear.
uniform sampler2D u_weatherMap;
uniform float u_weatherTexel;       // blocks per texel
// Render-space XZ + this = world XZ, wrapped to the maps' shared period.
uniform vec2 u_worldOffset;
// Terrain relief (TerrainRelief), world-anchored cells of u_cloudCell blocks:
// R = height; and RGBA = pile-up, baked steering shift x, z (blocks), steer.
uniform sampler2D u_terrainMap;
uniform sampler2D u_terrainFlowMap;
uniform float u_flowClipSink;
uniform vec2 u_flowCover;   // converge, pile-up coverage
uniform vec2 u_flowPass;    // mass from which cloud starts to ride over ground, and fully does

struct CloudColumn {
    float density;  // 0 = clear
    float base;     // first block of cloud
    float top;      // block above the last
    float reach;    // top before snapping to voxels: smooth, for shadows
    float build;    // 0..1: how far the column has grown toward its type's height
    float sigma;    // extinction per block at this density
    float albedo;
    float wet;      // wetness with the weather bias applied
    int type;
};

// CloudType.weights: how much of each type a column holds, indexed by
// CloudType ordinal; sums to 1.
vec4 cloudTypeWeights(float wet) {
    float b = u_cloudTypeBlend;
    float cu = smoothstep(-b, b, wet - u_cloudWetSplits.x);
    float ns = smoothstep(-b, b, wet - u_cloudWetSplits.y);
    float cb = smoothstep(-b, b, wet - u_cloudWetSplits.z);
    return vec4(cu * (1.0 - ns), 1.0 - cu, ns * (1.0 - cb), cb);
}

int cloudTypeOf(float wetness) {
    if (wetness >= u_cloudWetSplits.z) return 3; // CUMULONIMBUS
    if (wetness >= u_cloudWetSplits.y) return 2; // NIMBOSTRATUS
    if (wetness < u_cloudWetSplits.x) return 1;  // STRATUS
    return 0;                                    // CUMULUS
}

// CloudField.storminess: 0 below rain, 1 at storm, from the large-scale moisture.
float cloudStorminess(vec4 raw, vec2 bias) {
    return smoothstep(u_cloudWetSplits.y, u_cloudWetSplits.z, raw.g + bias.y);
}

// bias: x = coverage, y = wetness, as WeatherBias. Mirrors CloudField.resolve.
CloudColumn cloudResolve(vec4 raw, vec2 bias) {
    CloudColumn col;
    float organisation = smoothstep(u_cloudOrganised.y, u_cloudOrganised.z, raw.a);
    float storminess = cloudStorminess(raw, bias);
    float organised = organisation * mix(u_cloudOrganised.w, 1.0, storminess);
    float organisedStorm = organisation * storminess;
    // Organising only fills in, never clears what the cumulus grew (CloudField.coverage).
    float scattered = mix(raw.r, raw.a, u_cloudOrganisation);
    float merged = mix(raw.r, raw.a, u_cloudOrganised.x);
    float coverage = scattered + organised * max(0.0, merged - scattered)
            + u_cloudOrganisedLift.x * organisedStorm;
    float threshold = u_cloudThreshold - u_cloudBiasSpan * bias.x;
    col.density = smoothstep(threshold, threshold + u_cloudEdge, coverage);
    float cellular = mix(raw.g, raw.r, u_cloudCellWetness);
    float wet = mix(cellular, raw.g, organised) + u_cloudOrganisedLift.y * organisedStorm + bias.y;
    col.wet = wet;
    col.type = cloudTypeOf(wet);
    col.base = u_cloudTypeBase[col.type];
    float height = u_cloudTypeHeight[col.type];
    if (col.type == 3) {
        float floorH = u_cloudTypeHeight[2];
        height = floorH + (height - floorH)
                * smoothstep(u_cloudWetSplits.z, u_cloudWetSplits.z + u_cloudTowerRamp, wet);
    }
    float dome = smoothstep(threshold, threshold + u_cloudDomeRamp, coverage);
    col.build = dome * dome * (0.75 + 0.25 * raw.b);
    float voxels = max(1.0, floor(height / u_cloudVoxel * col.build + 0.5));
    col.top = col.base + voxels * u_cloudVoxel;
    col.reach = col.base + max(u_cloudVoxel, height * col.build);
    col.sigma = u_cloudTypeSigma[col.type] * col.density;
    col.albedo = u_cloudTypeAlbedo[col.type];
    return col;
}

// The cloud map at a cloud-space position through a cubic B-spline: smooth in
// value AND slope, so clouds and their shadows have no cell steps and no
// creases. (Bilinear filtering is only continuous in value; its slope breaks
// at every cell edge, which drew a vertical crease up every cloud wall.)
// Four bilinear taps, weighted, make the 4x4 B-spline.
vec4 cloudMapCubic(vec2 cloudXZ) {
    vec2 size = vec2(textureSize(u_cloudMap, 0));
    vec2 p = cloudXZ / u_cloudCell - 0.5;
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 g0 = w0 + w1, g1 = w2 + w3;
    vec2 h0 = (i - 0.5 + w1 / g0) / size;   // texel centres sit at i + 0.5
    vec2 h1 = (i + 1.5 + w3 / g1) / size;
    return g0.y * (g0.x * texture(u_cloudMap, vec2(h0.x, h0.y)) + g1.x * texture(u_cloudMap, vec2(h1.x, h0.y)))
         + g1.y * (g0.x * texture(u_cloudMap, vec2(h0.x, h1.y)) + g1.x * texture(u_cloudMap, vec2(h1.x, h1.y)));
}

// The relief under a render-space position; flat (zero) where none is loaded.
struct CloudRelief {
    float height;   // surface
    float pileup;   // 0..1 windward face of high ground
    vec2 shift;     // where light cloud here is drawn from, blocks
    float steer;    // 0..1 how hard the ground steers light cloud here
};

CloudRelief cloudReliefAt(vec2 renderXZ) {
    vec2 uv = (renderXZ + u_worldOffset) / (u_cloudCell * vec2(textureSize(u_terrainMap, 0)));
    vec4 f = texture(u_terrainFlowMap, uv);
    CloudRelief r;
    r.height = texture(u_terrainMap, uv).r;
    r.pileup = f.r;
    r.shift = f.gb;
    r.steer = f.a;
    return r;
}

// TerrainFlow.pileup: coverage bias from cloud clumping on a windward face.
float cloudPileup(CloudRelief r) {
    return u_flowCover.y * r.pileup;
}

// CloudField.mass: how much the cloud system here has clumped up, 0..1. Reads
// only the smooth organisation channel (organising left out, as in Java), so a
// whole cloud mass rides over a mountain or goes around it together.
float cloudMass(vec4 raw, vec2 bias) {
    float threshold = u_cloudThreshold - u_cloudBiasSpan * bias.x;
    float c = u_cloudOrganisation * raw.a + (1.0 - u_cloudOrganisation) * 0.5;
    return smoothstep(threshold, threshold + u_cloudDomeRamp, c);
}

// TerrainFlow.around: how the ground steers a cloud of `mass`, the same at
// every altitude so the cloud moves as one piece. xy = shift of the cloud-map
// lookup (blocks), z = coverage bias added.
vec3 cloudTerrainFlow(CloudRelief r, float mass) {
    float hold = 1.0 - smoothstep(u_flowPass.x, u_flowPass.y, mass);
    if (hold <= 0.0 || r.steer <= 0.0) return vec3(0.0);
    return vec3(r.shift * hold, u_flowCover.x * r.steer * hold);
}

// TerrainFlow.clip: 0 in the ground, 1 above it.
float cloudTerrainClip(CloudRelief r, float y) {
    return smoothstep(r.height - u_flowClipSink, r.height, y);
}

// The local weather at a render-space position (WeatherMap.sample).
vec2 cloudWeatherAt(vec2 renderXZ) {
    vec2 uv = (renderXZ + u_worldOffset) / (u_weatherTexel * vec2(textureSize(u_weatherMap, 0)));
    return texture(u_weatherMap, uv).rg;
}

// The cloud over render-space column xz, smoothly filtered (no cell steps),
// under the local weather, piled up and steered around the terrain `r` there
// (ClientWeather.columnAt). `shift` is how far the terrain moved the lookup:
// sample the cloud's noise there too, so a steered cloud keeps its own billows.
CloudColumn cloudColumnOver(vec2 xz, CloudRelief r, out vec2 shift) {
    vec2 bias = cloudWeatherAt(xz) + vec2(cloudPileup(r), 0.0);
    vec2 cloudXZ = xz + u_cloudOffset;
    vec4 raw = cloudMapCubic(cloudXZ);
    vec3 flow = cloudTerrainFlow(r, cloudMass(raw, bias));
    shift = flow.xy;
    if (flow.z > 0.0) {
        raw = cloudMapCubic(cloudXZ + flow.xy);
        bias.x += flow.z;
    }
    return cloudResolve(raw, bias);
}

CloudColumn cloudColumnAt(vec2 xz, out vec2 shift) {
    return cloudColumnOver(xz, cloudReliefAt(xz), shift);
}

// cloudColumnAt at render-space p, plus `clip`: how much of it survives the
// ground there (multiply it into density).
CloudColumn cloudColumnLocal(vec3 p, out float clip, out vec2 shift) {
    CloudRelief r = cloudReliefAt(p.xz);
    clip = cloudTerrainClip(r, p.y);
    return cloudColumnOver(p.xz, r, shift);
}

// CloudColumn.precipitation: rain or snow falling from a column, 0..1.
float cloudPrecipitation(CloudColumn c) {
    if (c.density <= 0.0) return 0.0;
    return c.density * dot(cloudTypeWeights(c.wet), u_cloudTypeRain)
            * mix(u_cloudSmallRain, 1.0, c.build);
}
