package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeHeightConfig;
import com.stonebreak.world.generation.biomes.BiomeHeightConfig.BiomeConfig;
import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Carves caves and surface overhangs out of would-be-solid terrain via 3D simplex noise.
 *
 * One sample per block in the active zone, no octave stacking, no smoothing pass.
 * Per-biome opt-in through {@link BiomeHeightConfig}'s caveIntensity / overhangIntensity.
 * Outside its zones (bedrock floor, above surface, biomes with intensity = 0) the
 * call is a constant-time fast path.
 */
public final class Density3D {
    /** Below this Y the world is always solid (protects bedrock floor). */
    private static final int CAVE_FLOOR = 8;
    /** Top N blocks of the column are governed by overhangIntensity, the rest by caveIntensity. */
    private static final int OVERHANG_DEPTH = 16;
    /** Noise wavelength in blocks. */
    private static final float SCALE = 1f / 26f;
    /** Vertical compression: caves elongate horizontally rather than become tall shafts. */
    private static final float Y_SQUASH = 1.8f;

    private final NoiseGenerator densityNoise;

    public Density3D(long seed) {
        this.densityNoise = new NoiseGenerator(seed + 17, 2, 0.5, 2.0);
    }

    /**
     * @param surfaceHeight final terrain height for this column (post-erosion)
     * @return true if the block should remain solid; false to carve to air
     */
    public boolean isSolid(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        if (y < CAVE_FLOOR || y >= surfaceHeight) {
            return true;
        }
        BiomeConfig cfg = BiomeHeightConfig.getConfig(biome);
        float intensity = (y >= surfaceHeight - OVERHANG_DEPTH)
            ? cfg.overhangIntensity
            : cfg.caveIntensity;
        if (intensity <= 0f) {
            return true;
        }
        float n = densityNoise.noise3D(
            worldX * SCALE,
            y * Y_SQUASH * SCALE,
            worldZ * SCALE
        );
        // Carve when noise is above the threshold. intensity=1 -> threshold 0 (~50% air);
        // intensity=0.25 -> threshold 0.5 (~25% air).
        return n < (1f - 2f * intensity);
    }
}
