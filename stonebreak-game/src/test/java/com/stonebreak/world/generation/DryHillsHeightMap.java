package com.stonebreak.world.generation;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Dry inland hills, well above sea level, with no water anywhere — the terrain caves are
 * meant to occupy.
 *
 * <p>Deliberately not the seed's own diffusion-bridge terrain. The bridge's real tiles put
 * roughly half their columns under water, and {@code WaterGuard} then seals nearly every
 * near-surface column as somebody's bank — correctly — and no cave may approach the surface
 * there. Measuring cave shape or reachability on it measures the seed's coastline, not the
 * carvers.
 *
 * <p>{@code BASE} sits at {@code SEA_LEVEL + 50}, which is dry inland ground. Every height
 * method is overridden so no call ever touches the (null) tile source, and all water reads
 * answer {@link TerrainTile#NO_WATER}.
 */
public final class DryHillsHeightMap extends HeightMapGenerator {

    private static final int BASE = WorldConfiguration.SEA_LEVEL + 50;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    public DryHillsHeightMap(long seed) {
        super(null);
    }

    /** Surface height at a column, exposed so tests can predict it without generating. */
    public static int height(int worldX, int worldZ) {
        double offset = Math.sin(worldX * 0.05) * 12 + Math.cos(worldZ * 0.07) * 12;
        return BASE + (int) Math.round(offset);
    }

    @Override
    public int generateHeight(int x, int z) {
        return height(x, z);
    }

    @Override
    public int waterLevel(int x, int z) {
        return TerrainTile.NO_WATER;
    }

    @Override
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] =
                        height(chunkX * CHUNK_SIZE + x, chunkZ * CHUNK_SIZE + z);
            }
        }
    }

    @Override
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out, int[] outWaterLevels) {
        populateChunkHeights(chunkX, chunkZ, out);
        if (outWaterLevels != null) {
            java.util.Arrays.fill(outWaterLevels, TerrainTile.NO_WATER);
        }
    }

    @Override
    public void populateHeightPatch(int minX, int minZ, int sizeX, int sizeZ, int[] out) {
        for (int x = 0; x < sizeX; x++) {
            int worldX = minX + x;
            for (int z = 0; z < sizeZ; z++) {
                int worldZ = minZ + z;
                out[x * sizeZ + z] = height(worldX, worldZ);
            }
        }
    }
}
