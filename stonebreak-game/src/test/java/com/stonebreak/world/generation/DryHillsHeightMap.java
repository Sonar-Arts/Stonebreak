package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Dry inland hills, well above sea level, with no water anywhere — the terrain caves are
 * meant to occupy.
 *
 * <p>Deliberately not the seed's own noise terrain, which puts roughly half its columns under
 * water. {@code WaterGuard} then seals nearly every near-surface column as somebody's bank —
 * correctly — and no cave may approach the surface there. Measuring cave shape or reachability
 * on it measures the seed's coastline, not the carvers.
 *
 * <p>{@code BASE} sits at {@code SEA_LEVEL + 50}, which is representative inland ground on this
 * branch: the height splines put typical land at 90-130 and peaks near 200. That leaves ~114
 * blocks of rock, which is what every carver's depth range is tuned against.
 *
 * <p>Overriding a height oracle means overriding <em>all</em> of its height methods — the
 * batched fills do not route through {@link #generateHeight}, by design, so a subclass that
 * overrode only the point method would hand the carvers one profile and the block fill another.
 */
public final class DryHillsHeightMap extends HeightMapGenerator {

    private static final int BASE = WorldConfiguration.SEA_LEVEL + 50;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    public DryHillsHeightMap(long seed) {
        super(new NoiseRouter(seed));
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
    public int baseHeight(int x, int z) {
        return height(x, z);
    }

    @Override
    public int shapedHeight(int x, int z) {
        return height(x, z);
    }

    @Override
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = height(chunkX * CHUNK_SIZE + x, chunkZ * CHUNK_SIZE + z);
            }
        }
    }

}
