// Cloud field on the GPU: the raw noise map plus the rule that resolves it
// into columns under the current weather. Shared by the cloud raymarch and
// every receiver of cloud shadows. The rule mirrors CloudField.resolve and
// CloudType.forWetness in Java — change them together. Every number it uses
// arrives as a uniform from CloudUniforms, so the tuning lives only in Java.

// RGBA8, GL_REPEAT: R = coverage, G = wetness, B = detail (see CloudField.raw).
// Texel (i, j) holds cloud-space cell (i, j) modulo the map size.
uniform sampler2D u_cloudMap;
// Render-space XZ + this = cloud-space XZ, wrapped to the map period so it
// stays small enough for float precision.
uniform vec2 u_cloudOffset;
uniform float u_cloudCell;          // blocks per cell
uniform float u_cloudVoxel;         // vertical quantum, blocks
uniform float u_cloudThreshold;     // coverage noise where cloud starts
uniform float u_cloudEdge;          // noise range from wisp to full density
uniform float u_cloudDomeRamp;      // noise range from flat to full height
uniform float u_cloudTowerRamp;     // wetness range from storm floor to full tower
uniform float u_cloudWetBias;
uniform vec3 u_cloudWetSplits;      // stratus max, nimbostratus min, cumulonimbus min
// Per type, indexed by CloudType ordinal.
uniform vec4 u_cloudTypeBase;
uniform vec4 u_cloudTypeHeight;
uniform vec4 u_cloudTypeSigma;
uniform vec4 u_cloudTypeAlbedo;

struct CloudColumn {
    float density;  // 0 = clear
    float base;     // first block of cloud
    float top;      // block above the last
    float build;    // 0..1: how far the column has grown toward its type's height
    float sigma;    // extinction per block at this density
    float albedo;
    float wet;      // wetness with the weather bias applied
    int type;
};

int cloudTypeOf(float wetness) {
    if (wetness >= u_cloudWetSplits.z) return 3; // CUMULONIMBUS
    if (wetness >= u_cloudWetSplits.y) return 2; // NIMBOSTRATUS
    if (wetness < u_cloudWetSplits.x) return 1;  // STRATUS
    return 0;                                    // CUMULUS
}

CloudColumn cloudResolve(vec4 raw) {
    CloudColumn col;
    col.density = smoothstep(u_cloudThreshold, u_cloudThreshold + u_cloudEdge, raw.r);
    float wet = raw.g + u_cloudWetBias;
    col.wet = wet;
    col.type = cloudTypeOf(wet);
    col.base = u_cloudTypeBase[col.type];
    float height = u_cloudTypeHeight[col.type];
    if (col.type == 3) {
        float floorH = u_cloudTypeHeight[2];
        height = floorH + (height - floorH)
                * smoothstep(u_cloudWetSplits.z, u_cloudWetSplits.z + u_cloudTowerRamp, wet);
    }
    float dome = smoothstep(u_cloudThreshold, u_cloudThreshold + u_cloudDomeRamp, raw.r);
    col.build = dome * dome * (0.75 + 0.25 * raw.b);
    float voxels = max(1.0, floor(height / u_cloudVoxel * col.build + 0.5));
    col.top = col.base + voxels * u_cloudVoxel;
    col.sigma = u_cloudTypeSigma[col.type] * col.density;
    col.albedo = u_cloudTypeAlbedo[col.type];
    return col;
}

// The column over a cloud-space cell, as rain and lightning see it.
CloudColumn cloudColumnAt(ivec2 cell) {
    ivec2 size = textureSize(u_cloudMap, 0);
    ivec2 texel = ivec2(mod(vec2(cell), vec2(size)));
    return cloudResolve(texelFetch(u_cloudMap, texel, 0));
}

// A bilinearly smoothed column at a cloud-space position — soft-edged, so
// clouds and their shadows have no cell steps.
CloudColumn cloudColumnSmooth(vec2 cloudXZ) {
    vec2 uv = cloudXZ / (u_cloudCell * vec2(textureSize(u_cloudMap, 0)));
    return cloudResolve(texture(u_cloudMap, uv));
}
