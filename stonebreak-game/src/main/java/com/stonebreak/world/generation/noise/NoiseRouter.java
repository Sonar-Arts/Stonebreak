package com.stonebreak.world.generation.noise;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Single owner of 2D world-generation noise channels.
 *
 * Channels are sampled through {@link NoiseChannel2D}, whose backend is chosen
 * once by {@link TerrainNoise} (native FastNoise2 when the Cenda kernels
 * library is present, the classic Java simplex otherwise). Per-point and
 * batched sampling agree bit-for-bit at the same coordinate on both backends.
 *
 * Seed offset map (each channel must stay distinct):
 *   seed + 0 : moisture
 *   seed + 1 : temperature
 *   seed + 2 : continentalness       (land/ocean shape)
 *   seed + 3 : surface detail        (small ±3 block fuzz)
 *   seed + 5 : erosion               (large-scale mountainousness gate)
 *   seed + 8 : peaks and valleys     (ridged mountain channel)
 *   seed + 17: density3D (owned by Density3D, listed for reference)
 *   seed + 4 : climate continentalness (RETIRED - folded into channel 2)
 */
public final class NoiseRouter {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    /** Blocks above sea level per unit temperature drop. */
    private static final float ALTITUDE_CHILL_FACTOR = 200f;

    private static final float MOISTURE_SCALE         = 1f / 1500f;
    private static final float TEMPERATURE_SCALE      = 1f / 2000f;
    private static final float CONTINENTALNESS_SCALE  = 1f / 800f;
    private static final float DETAIL_SCALE           = 1f / 45f;
    private static final float EROSION_SCALE          = 1f / 900f;
    private static final float PEAKS_VALLEYS_SCALE    = 1f / 260f;

    // Legacy channel offsets are applied in NOISE space (x*scale + offset). The
    // native path needs the same shift in whole BLOCKS: offsetNoise / scale.
    // All six scales divide their offsets exactly, so these stay integers.
    private static final int MOISTURE_OFF_BLOCKS      = 150_000;   // +100 * 1500
    private static final int TEMPERATURE_OFF_BLOCKS   = -100_000;  // -50 * 2000
    private static final int EROSION_X_OFF_BLOCKS     = 20_700;    // +23 * 900
    private static final int EROSION_Z_OFF_BLOCKS     = -15_300;   // -17 * 900
    private static final int PV_X_OFF_BLOCKS          = 80_860;    // +311 * 260
    private static final int PV_Z_OFF_BLOCKS          = 20_020;    // +77 * 260

    private final NoiseChannel2D moistureNoise;
    private final NoiseChannel2D temperatureNoise;
    private final NoiseChannel2D continentalnessNoise;
    private final NoiseChannel2D detailNoise;
    private final NoiseChannel2D erosionNoise;
    private final NoiseChannel2D peaksValleysNoise;

    public NoiseRouter(long seed) {
        this.moistureNoise = TerrainNoise.channel2D(seed, 6, 0.45, 2.1,
            MOISTURE_SCALE, 100f, 100f, MOISTURE_OFF_BLOCKS, MOISTURE_OFF_BLOCKS);
        this.temperatureNoise = TerrainNoise.channel2D(seed + 1, 6, 0.40, 2.0,
            TEMPERATURE_SCALE, -50f, -50f, TEMPERATURE_OFF_BLOCKS, TEMPERATURE_OFF_BLOCKS);
        this.continentalnessNoise = TerrainNoise.channel2D(seed + 2, 8, 0.45, 2.0,
            CONTINENTALNESS_SCALE, 0f, 0f, 0, 0);
        this.detailNoise = TerrainNoise.channel2D(seed + 3, 3, 0.50, 2.0,
            DETAIL_SCALE, 0f, 0f, 0, 0);
        this.erosionNoise = TerrainNoise.channel2D(seed + 5, 5, 0.40, 2.0,
            EROSION_SCALE, 23f, -17f, EROSION_X_OFF_BLOCKS, EROSION_Z_OFF_BLOCKS);
        this.peaksValleysNoise = TerrainNoise.channel2D(seed + 8, 5, 0.45, 2.0,
            PEAKS_VALLEYS_SCALE, 311f, 77f, PV_X_OFF_BLOCKS, PV_Z_OFF_BLOCKS);
    }

    /** Moisture in [0, 1]. */
    public float moisture(int x, int z) {
        return moistureFromRaw(moistureNoise.sample(x, z));
    }

    /** Temperature in [0, 1], cooled by altitude above sea level. */
    public float temperature(int x, int z, int height) {
        return temperatureFromRaw(temperatureNoise.sample(x, z), height);
    }

    /** Sea-level temperature convenience. */
    public float temperature(int x, int z) {
        return temperature(x, z, SEA_LEVEL);
    }

    /** Continentalness in [-1, 1]. Drives base elevation. */
    public float continentalness(int x, int z) {
        return continentalnessNoise.sample(x, z);
    }

    /** Erosion in [-1, 1]. High erosion flattens peaks; low erosion preserves them. */
    public float erosion(int x, int z) {
        return erosionNoise.sample(x, z);
    }

    /** Peaks/valleys in [-1, 1]. Signed ridged mountain channel. */
    public float peaksValleys(int x, int z) {
        return peaksValleysNoise.sample(x, z);
    }

    /** Surface detail noise in [-1, 1] for small per-column variation. */
    public float detail(int x, int z) {
        return detailNoise.sample(x, z);
    }

    /** Bundled sample of the full climate tuple at a position. */
    public MultiNoiseSample sample(int x, int z, int height) {
        return new MultiNoiseSample(
            continentalness(x, z),
            erosion(x, z),
            peaksValleys(x, z),
            temperature(x, z, height),
            moisture(x, z)
        );
    }

    /**
     * Batch-fills the three shape channels + detail for a grid, each indexed
     * {@code [ix*countZ + iz]} at block positions {@code (baseX + ix*stride,
     * baseZ + iz*stride)}. Values are identical to the per-point accessors.
     */
    public void fillShapeChannels(int baseX, int baseZ, int countX, int countZ, int stride,
                                  float[] c, float[] pv, float[] e, float[] d) {
        continentalnessNoise.fill(c, baseX, baseZ, countX, countZ, stride);
        peaksValleysNoise.fill(pv, baseX, baseZ, countX, countZ, stride);
        erosionNoise.fill(e, baseX, baseZ, countX, countZ, stride);
        detailNoise.fill(d, baseX, baseZ, countX, countZ, stride);
    }

    /**
     * Batch-fills the RAW temperature and moisture channels (pre-transform;
     * combine with {@link #temperatureFromRaw} / {@link #moistureFromRaw}).
     */
    public void fillClimateChannels(int baseX, int baseZ, int countX, int countZ, int stride,
                                    float[] tRaw, float[] mRaw) {
        temperatureNoise.fill(tRaw, baseX, baseZ, countX, countZ, stride);
        moistureNoise.fill(mRaw, baseX, baseZ, countX, countZ, stride);
    }

    /**
     * Creates the native carver terrain context: the four shape channels
     * (c, pv, e, d) with EXACTLY this router's parameters, plus the height
     * splines. Returns 0 when the native kernels are unavailable. The caller
     * owns the handle (destroy via {@link TerrainNoise#destroyTerrainOnCollect}).
     */
    public static long createCarverTerrainContext(long seed,
                                                  double[] splineXs, double[] splineYs,
                                                  int[] splineSizes, float detailAmplitude) {
        ShapeChannelParams ch = shapeChannelParams(seed);
        return com.openmason.engine.cenda.CendaKernels.terrainCreate(seed,
            ch.seeds(), ch.octaves(), ch.gain(), ch.lacunarity(), ch.freq(), ch.xOff(), ch.zOff(),
            splineXs, splineYs, splineSizes, detailAmplitude);
    }

    /**
     * The four shape-channel configs (c, pv, e, d) exactly as this router
     * builds them — the single packing shared by the native carver and the
     * fused chunk-generator contexts.
     */
    public record ShapeChannelParams(int[] seeds, int[] octaves, float[] gain,
                                     float[] lacunarity, float[] freq,
                                     int[] xOff, int[] zOff) {}

    public static ShapeChannelParams shapeChannelParams(long seed) {
        int[] seeds = {
            TerrainNoise.nativeSeed(seed + 2), // continentalness
            TerrainNoise.nativeSeed(seed + 8), // peaks/valleys
            TerrainNoise.nativeSeed(seed + 5), // erosion
            TerrainNoise.nativeSeed(seed + 3), // detail
        };
        int[] octaves = {8, 5, 5, 3};
        float[] gain = {0.45f, 0.45f, 0.40f, 0.50f};
        float[] lacunarity = {2.0f, 2.0f, 2.0f, 2.0f};
        float[] freq = {CONTINENTALNESS_SCALE, PEAKS_VALLEYS_SCALE, EROSION_SCALE, DETAIL_SCALE};
        int[] xOff = {0, PV_X_OFF_BLOCKS, EROSION_X_OFF_BLOCKS, 0};
        int[] zOff = {0, PV_Z_OFF_BLOCKS, EROSION_Z_OFF_BLOCKS, 0};
        return new ShapeChannelParams(seeds, octaves, gain, lacunarity, freq, xOff, zOff);
    }

    /** Raw moisture channel value → [0, 1]. */
    public static float moistureFromRaw(float raw) {
        return raw * 0.5f + 0.5f;
    }

    /** Raw temperature channel value → [0, 1] with altitude chill. */
    public static float temperatureFromRaw(float raw, int height) {
        float base = raw * 0.5f + 0.5f;
        if (height > SEA_LEVEL) {
            base -= (height - SEA_LEVEL) / ALTITUDE_CHILL_FACTOR;
        }
        return Math.max(0f, Math.min(1f, base));
    }
}
