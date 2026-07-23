package com.stonebreak.world.generation.heightmap;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.biomes.BiomeSurfaceConfig;
import com.stonebreak.world.generation.biomes.BiomeSurfaceConfig.Entry;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Carves caves and surface overhangs out of would-be-solid terrain via 3D simplex noise.
 *
 * One sample per block in the active zone, no octave stacking, no smoothing pass.
 * Per-biome opt-in through {@link BiomeSurfaceConfig}'s caveIntensity / overhangIntensity.
 * Outside its zones (bedrock floor, above surface, biomes with intensity = 0) the
 * call is a constant-time fast path.
 *
 * Backends: on the native (FastNoise2) backend the chunk pipeline should call
 * {@link #prepareChunk} once per chunk and query the returned {@link Field} —
 * one SIMD volume fill replaces tens of thousands of per-block samples. The
 * per-point {@link #isSolid} remains as the Java-backend path (and produces
 * classic terrain there).
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

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final NoiseGenerator densityNoise;
    private final long nativeNode;
    private final int nativeSeed;

    public Density3D(long seed) {
        this.densityNoise = new NoiseGenerator(seed + 17, 2, 0.5, 2.0);
        this.nativeNode = TerrainNoise.native3DNode(2, 0.5, 2.0, SCALE);
        this.nativeSeed = TerrainNoise.nativeSeed(seed + 17);
        TerrainNoise.destroyOnCollect(this, nativeNode);
    }

    /** Density-node parameters for the fused native generator — must mirror the constructor exactly. */
    public record NodeParams(int seed, int octaves, float gain, float lacunarity, float frequency) {}

    /** The exact node parameters this class would build for {@code worldSeed}. */
    public static NodeParams nodeParams(long worldSeed) {
        return new NodeParams(TerrainNoise.nativeSeed(worldSeed + 17), 2, 0.5f, 2.0f, SCALE);
    }

    /**
     * @param surfaceHeight final terrain height for this column (post-erosion)
     * @return true if the block should remain solid; false to carve to air
     */
    public boolean isSolid(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        if (y < CAVE_FLOOR || y >= surfaceHeight) {
            return true;
        }
        Entry cfg = BiomeSurfaceConfig.get(biome);
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
        return carve(n, intensity);
    }

    /**
     * Batch-fills the chunk's cave-noise volume in one native call. Returns
     * null on the Java backend (or when no column reaches above the cave
     * floor) — callers then use per-point {@link #isSolid}.
     *
     * @param heights the chunk's 16x16 final-height grid, indexed [x*16+z]
     */
    public Field prepareChunk(int chunkX, int chunkZ, int[] heights) {
        if (nativeNode == 0L) {
            return null;
        }
        int maxSurface = 0;
        for (int h : heights) {
            maxSurface = Math.max(maxSurface, h);
        }
        if (maxSurface <= CAVE_FLOOR) {
            return null;
        }
        int yCount = maxSurface - CAVE_FLOOR;
        float[] volume = new float[yCount * CHUNK_SIZE * CHUNK_SIZE];
        // FastNoise2 axis mapping (X innermost): fnX = worldZ, fnY = worldX,
        // fnZ = squashed Y — output lands as [(y-CAVE_FLOOR)*256 + x*16 + z]
        // with no reshuffle. Frequency (SCALE) is inside the node.
        boolean ok = CendaKernels.fillGrid3D(nativeNode, volume,
            (float) (chunkZ * CHUNK_SIZE), (float) (chunkX * CHUNK_SIZE), CAVE_FLOOR * Y_SQUASH,
            CHUNK_SIZE, CHUNK_SIZE, yCount,
            1f, 1f, Y_SQUASH,
            nativeSeed);
        if (!ok) {
            throw new IllegalStateException("Cenda 3D density fill failed");
        }
        return new Field(volume, yCount);
    }

    /** Per-chunk cave-noise volume produced by {@link #prepareChunk}. */
    public static final class Field {
        private final float[] volume;
        private final int yCount;

        private Field(float[] volume, int yCount) {
            this.volume = volume;
            this.yCount = yCount;
        }

        /** Same contract as {@link Density3D#isSolid}, with chunk-local x/z. */
        public boolean isSolid(int localX, int y, int localZ, int surfaceHeight, BiomeType biome) {
            if (y < CAVE_FLOOR || y >= surfaceHeight) {
                return true;
            }
            Entry cfg = BiomeSurfaceConfig.get(biome);
            float intensity = (y >= surfaceHeight - OVERHANG_DEPTH)
                ? cfg.overhangIntensity
                : cfg.caveIntensity;
            if (intensity <= 0f) {
                return true;
            }
            int yIndex = y - CAVE_FLOOR;
            if (yIndex >= yCount) {
                return true;
            }
            float n = volume[(yIndex * CHUNK_SIZE + localX) * CHUNK_SIZE + localZ];
            return carve(n, intensity);
        }
    }

    // Carve when noise is above the threshold. intensity=1 -> threshold 0 (~50% air);
    // intensity=0.25 -> threshold 0.5 (~25% air).
    private static boolean carve(float noise, float intensity) {
        return noise < (1f - 2f * intensity);
    }
}
