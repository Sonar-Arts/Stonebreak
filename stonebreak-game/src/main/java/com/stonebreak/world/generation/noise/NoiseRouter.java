package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Single owner of 2D world-generation noise channels.
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

    private final NoiseGenerator moistureNoise;
    private final NoiseGenerator temperatureNoise;
    private final NoiseGenerator continentalnessNoise;
    private final NoiseGenerator detailNoise;
    private final NoiseGenerator erosionNoise;
    private final NoiseGenerator peaksValleysNoise;

    public NoiseRouter(long seed) {
        this.moistureNoise        = new NoiseGenerator(seed,     6, 0.45, 2.1);
        this.temperatureNoise     = new NoiseGenerator(seed + 1, 6, 0.40, 2.0);
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.detailNoise          = new NoiseGenerator(seed + 3, 3, 0.50, 2.0);
        this.erosionNoise         = new NoiseGenerator(seed + 5, 5, 0.40, 2.0);
        this.peaksValleysNoise    = new NoiseGenerator(seed + 8, 5, 0.45, 2.0);
    }

    /** Moisture in [0, 1]. */
    public float moisture(int x, int z) {
        return moistureNoise.noise(x * MOISTURE_SCALE + 100f, z * MOISTURE_SCALE + 100f) * 0.5f + 0.5f;
    }

    /** Temperature in [0, 1], cooled by altitude above sea level. */
    public float temperature(int x, int z, int height) {
        float base = temperatureNoise.noise(x * TEMPERATURE_SCALE - 50f, z * TEMPERATURE_SCALE - 50f) * 0.5f + 0.5f;
        if (height > SEA_LEVEL) {
            base -= (height - SEA_LEVEL) / ALTITUDE_CHILL_FACTOR;
        }
        return Math.max(0f, Math.min(1f, base));
    }

    /** Sea-level temperature convenience. */
    public float temperature(int x, int z) {
        return temperature(x, z, SEA_LEVEL);
    }

    /** Continentalness in [-1, 1]. Drives base elevation. */
    public float continentalness(int x, int z) {
        return continentalnessNoise.noise(x * CONTINENTALNESS_SCALE, z * CONTINENTALNESS_SCALE);
    }

    /** Erosion in [-1, 1]. High erosion flattens peaks; low erosion preserves them. */
    public float erosion(int x, int z) {
        return erosionNoise.noise(x * EROSION_SCALE + 23f, z * EROSION_SCALE - 17f);
    }

    /** Peaks/valleys in [-1, 1]. Signed ridged mountain channel. */
    public float peaksValleys(int x, int z) {
        return peaksValleysNoise.noise(x * PEAKS_VALLEYS_SCALE + 311f, z * PEAKS_VALLEYS_SCALE + 77f);
    }

    /** Surface detail noise in [-1, 1] for small per-column variation. */
    public float detail(int x, int z) {
        return detailNoise.noise(x * DETAIL_SCALE, z * DETAIL_SCALE);
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
}
