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
    /** Blocks above sea level per unit temperature drop. */
    private static final float ALTITUDE_CHILL_FACTOR = 200f;

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

    public NoiseRouter(long seed) {
        this.seed = seed;
        this.moistureNoise = TerrainNoise.channel2D(seed, 6, 0.45, 2.1,
            MOISTURE_SCALE, 100f, 100f, MOISTURE_OFF_BLOCKS, MOISTURE_OFF_BLOCKS);
        this.temperatureNoise = TerrainNoise.channel2D(seed + 1, 6, 0.40, 2.0,
            TEMPERATURE_SCALE, -50f, -50f, TEMPERATURE_OFF_BLOCKS, TEMPERATURE_OFF_BLOCKS);
        this.shape = new DistilledShape(seed);
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

    /**
     * Creates the native carver terrain context: the distilled shape's channels, constants and
     * height-curve table (SBW ABI 2). Returns 0 when the game's worldgen natives are unavailable.
     * The caller owns the handle (destroy via {@link TerrainNoise#destroyTerrainOnCollect}).
     */
    public static long createCarverTerrainContext(long seed) {
        DistilledShape.NativeArgs a = new DistilledShape(seed).nativeArgs();
        return com.stonebreak.world.generation.WorldgenNatives.terrainCreate(seed,
            a.seeds(), a.octaves(), a.gain(), a.lacunarity(), a.freq(), a.xOff(), a.zOff(),
            a.constants(), a.curveTable(), a.sizes(), a.detailBlocks());
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
