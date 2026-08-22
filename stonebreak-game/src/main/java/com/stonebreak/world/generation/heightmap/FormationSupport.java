package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.BitSet;

/**
 * Drops stalagmites and stalactites that nothing holds up.
 *
 * <p>{@link CavernCarver} and {@link MegaCavernCarver} grow their formations against their
 * own carve mask, which is the only thing they can see. By the time a chunk is filled that
 * mask has been unioned with the worm tunnels, the ravines and the sinkholes, and the noise
 * field in {@link Density3D} has carved cells that never appear in any mask at all. Any of
 * those can take out the block a pillar was standing on — and formations are written as STONE
 * <em>before</em> the carve mask is applied, so the pillar itself survives the cut that
 * removed its floor. The result is a stone column hanging in the middle of a room.
 *
 * <p>This runs where that is knowable: after every carver has contributed and the density
 * field is prepared, but before the block fill. Each contiguous run of formation cells in a
 * column keeps its cells only if the block immediately below the run, or the one immediately
 * above it, is solid in the finished chunk. A run held at neither end is cleared whole —
 * partial rescue would just leave a shorter floating pillar.
 *
 * <p>Anchor cells are never formation cells themselves: a run's ends are where the formation
 * stops, so the block being tested is ordinary terrain and {@link Support} does not need to
 * know about the formation mask it is pruning.
 */
public final class FormationSupport {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private FormationSupport() {
    }

    /**
     * Whether a chunk-local cell holds a block a formation can hang from, in the chunk as it
     * will finally be written.
     *
     * <p>An interface rather than the pieces it is made of ({@code caveMask}, the density
     * field, heights, biomes) because the caller already owns the one expression that decides
     * this — the head of its block fill — and both backends spell it differently.
     */
    @FunctionalInterface
    public interface Support {
        boolean isSolid(int localX, int y, int localZ);
    }

    /**
     * Clears every unsupported formation cell from {@code formations} in place.
     *
     * <p>Cheap when there is nothing to do, which is the common case: caverns are 1-in-48
     * chunks and the mask is empty in the rest.
     */
    public static void prune(BitSet formations, Support support) {
        if (formations.isEmpty()) {
            return;
        }
        boolean[] done = new boolean[CHUNK_SIZE * CHUNK_SIZE];
        for (int bit = formations.nextSetBit(0); bit >= 0; bit = formations.nextSetBit(bit + 1)) {
            int x = CarveMaskKey.x(bit);
            int z = CarveMaskKey.z(bit);
            int column = x * CHUNK_SIZE + z;
            if (done[column]) {
                continue;
            }
            done[column] = true;
            pruneColumn(formations, support, x, z);
        }
    }

    /** One column: walk its runs bottom-up, clearing any that neither end supports. */
    private static void pruneColumn(BitSet formations, Support support, int x, int z) {
        int runStart = -1;
        for (int y = 1; y <= WORLD_HEIGHT; y++) {
            if (y < WORLD_HEIGHT && formations.get(CarveMaskKey.pack(x, y, z))) {
                if (runStart < 0) {
                    runStart = y;
                }
                continue;
            }
            if (runStart < 0) {
                continue;
            }
            int runEnd = y - 1;
            if (!support.isSolid(x, runStart - 1, z) && !support.isSolid(x, runEnd + 1, z)) {
                for (int cy = runStart; cy <= runEnd; cy++) {
                    formations.clear(CarveMaskKey.pack(x, cy, z));
                }
            }
            runStart = -1;
        }
    }
}
