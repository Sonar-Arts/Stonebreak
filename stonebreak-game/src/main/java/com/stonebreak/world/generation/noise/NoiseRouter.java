package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.heightmap.DistilledShape;

/**
 * Single owner of the Standard generator's 2D noise: the climate channels (temperature,
 * moisture) and the terrain {@link DistilledShape} (continents, ranges, hill provinces, relief),
 * so the height map, the biome manager and the native contexts all read one instance.
 *
 * Channels are sampled through {@link NoiseChannel2D}, whose backend is chosen once by
 * {@link TerrainNoise} (native FastNoise2 when the Cenda kernels library is present, the classic
 * Java simplex otherwise). Per-point and batched sampling agree bit-for-bit at the same coordinate
 * on both backends.
 *
 * Seed offset map (each channel must stay distinct):
 *   seed + 0 : moisture
 *   seed + 1 : temperature
 *   seed + 2, 3, 8, 21..28 : DistilledShape (continent, detail, range, warp, spur, along, hills,
 *                            hilly, valley, texture)
 *   seed + 17: density3D (owned by Density3D, listed for reference)
 *   seed + 4, 5 : RETIRED (old climate continentalness, erosion)
 */
public final class NoiseRouter {

    private static final int SEA_LEVEL = StandardTerrain.SEA_LEVEL;
    /**
     * Altitude chill starts at the floor of the hill band (BiomeSelector's calibrated y 110), not at
     * sea level. The distilled shape stands most land at y 64..110, and chilling from sea level
     * pushed ~52% of land into cold biomes (87% of mountain bodies to ICE_FIELDS); lowlands now keep
     * their climate and only hills and ranges cool (~32% cold land, peaks split ice/stony).
     */
    private static final int ALTITUDE_CHILL_START_Y = 110;
    /** Blocks above {@link #ALTITUDE_CHILL_START_Y} per unit temperature drop. */
    private static final float ALTITUDE_CHILL_FACTOR = 250f;

    private static final float MOISTURE_SCALE         = 1f / 1500f;
    private static final float TEMPERATURE_SCALE      = 1f / 2000f;

    // Legacy channel offsets are applied in NOISE space (x*scale + offset). The
    // native path needs the same shift in whole BLOCKS: offsetNoise / scale.
    private static final int MOISTURE_OFF_BLOCKS      = 150_000;   // +100 * 1500
    private static final int TEMPERATURE_OFF_BLOCKS   = -100_000;  // -50 * 2000

    private final long seed;
    private final NoiseChannel2D moistureNoise;
    private final NoiseChannel2D temperatureNoise;
    private final DistilledShape shape;
    /** Standard shape 2: the climate remap fitted to DaedalusTGM-Exp-2 (BiomeSelector BIOMES_V2). */
    private final boolean v2;

    /** The 2026-11-01 router (Standard shape 1). */
    public NoiseRouter(long seed) {
        this(seed, com.stonebreak.world.generation.StandardShapeVersion.V1);
    }

    /** The router of a world created with the Standard shape {@code version} (null = the current one). */
    public NoiseRouter(long seed, com.stonebreak.world.generation.StandardShapeVersion version) {
        this.seed = seed;
        this.moistureNoise = TerrainNoise.channel2D(seed, 6, 0.45, 2.1,
            MOISTURE_SCALE, 100f, 100f, MOISTURE_OFF_BLOCKS, MOISTURE_OFF_BLOCKS);
        this.temperatureNoise = TerrainNoise.channel2D(seed + 1, 6, 0.40, 2.0,
            TEMPERATURE_SCALE, -50f, -50f, TEMPERATURE_OFF_BLOCKS, TEMPERATURE_OFF_BLOCKS);
        this.shape = new DistilledShape(seed, version);
        this.v2 = shape.isV2();
    }

    /** True for a Standard shape 2 world's router (its selector: {@code new BiomeSelector(true)}). */
    public boolean isV2() {
        return v2;
    }

    public long seed() {
        return seed;
    }

    /** The terrain shape every Standard height comes from. */
    public DistilledShape shape() {
        return shape;
    }

    /** Moisture in [0, 1]. */
    public float moisture(int x, int z) {
        float raw = moistureNoise.sample(x, z);
        return v2 ? moistureFromRawV2(raw) : moistureFromRaw(raw);
    }

    /** Temperature in [0, 1], cooled by altitude above the hill band. */
    public float temperature(int x, int z, int height) {
        float raw = temperatureNoise.sample(x, z);
        return v2 ? temperatureFromRawV2(raw, height) : temperatureFromRaw(raw, height);
    }

    /** Standard shape 2's moisture (native moistureV2). */
    public static float moistureFromRawV2(float raw) {
        return Math.max(0f, Math.min(1f, raw * com.stonebreak.world.generation.biomes.BiomeSelector.M2_SCALE
            + com.stonebreak.world.generation.biomes.BiomeSelector.M2_OFFSET));
    }

    /** Standard shape 2's temperature (native temperatureV2). */
    public static float temperatureFromRawV2(float raw, int height) {
        float base = raw * com.stonebreak.world.generation.biomes.BiomeSelector.T2_SCALE
            + com.stonebreak.world.generation.biomes.BiomeSelector.T2_OFFSET;
        int start = com.stonebreak.world.generation.biomes.BiomeSelector.CHILL2_START_Y;
        if (height > start) {
            base -= (height - start) / com.stonebreak.world.generation.biomes.BiomeSelector.CHILL2_FACTOR;
        }
        return Math.max(0f, Math.min(1f, base));
    }

    /** Sea-level temperature convenience. */
    public float temperature(int x, int z) {
        return temperature(x, z, SEA_LEVEL);
    }

    /** Bundled biome inputs at a position: shape fields of the column + climate at its shaped height. */
    public MultiNoiseSample sample(int x, int z) {
        DistilledShape.Column c = new DistilledShape.Column();
        shape.column(x, z, c);
        return sampleOf(c, temperature(x, z, c.shapedHeight), moisture(x, z));
    }

    /** The biome tuple of an already-evaluated column. */
    public static MultiNoiseSample sampleOf(DistilledShape.Column c, float temperature, float moisture) {
        return new MultiNoiseSample((float) c.land, (float) c.mountain, (float) c.hilly, (float) c.rough,
            c.shapedHeight, temperature, moisture);
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

    /** Raw moisture channel value → [0, 1]. */
    public static float moistureFromRaw(float raw) {
        return raw * 0.5f + 0.5f;
    }

    /** Raw temperature channel value → [0, 1] with altitude chill above the hill band. */
    public static float temperatureFromRaw(float raw, int height) {
        float base = raw * 0.5f + 0.5f;
        if (height > ALTITUDE_CHILL_START_Y) {
            base -= (height - ALTITUDE_CHILL_START_Y) / ALTITUDE_CHILL_FACTOR;
        }
        return Math.max(0f, Math.min(1f, base));
    }
}
