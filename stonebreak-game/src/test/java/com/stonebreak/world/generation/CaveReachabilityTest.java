package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caves must be reachable from the surface.
 *
 * <p>This is the test that would have caught the bug this whole rework exists for. The cave
 * carvers were correct in isolation and every existing test passed, but the diffusion
 * migration had rescaled their Y bands into fractions of {@code SEA_LEVEL} — putting worms at
 * y=70..250 and caverns at y=50..150 while land surfaces sit at 340-500. A carver climbs at
 * most {@code MAX_STEPS * sin(PITCH_MAX)} blocks, so nothing could ever breach. The world
 * generated a full cave network, correctly, sealed under a hundred-odd blocks of stone, with
 * no entrance anywhere. Nothing measured connectivity, so nothing noticed.
 *
 * <p>So the assertion here is deliberately about the property a player experiences — "can I
 * get in" — rather than about any carver's internals. It floods air inward from open sky and
 * asks how much of the carved volume it arrives at.
 */
public class CaveReachabilityTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /**
     * Region swept, in chunks per side.
     *
     * <p>Sized by the rarest feature, not by convenience. Ravines spawn 1-in-110 chunks and
     * reach only ~65 blocks from their anchor, so a 4x4 region contains one well under half
     * the time — and a reachability number that depends on whether a ravine happened to land
     * is not a measurement, it is a coin flip. 10x10 makes it ~1 expected inside the region
     * and more within reach of its edges.
     */
    private static final int REGION = 10;

    /**
     * Ratchet floor on the share of carved volume reachable from the surface.
     *
     * <p><b>Raise this as the cave system improves; never lower it.</b> It is a regression
     * floor, not a target. History:
     *
     * <ul>
     *   <li>0.000 — the bug: carver Y bands keyed to SEA_LEVEL, nothing reachable at all
     *   <li>0.019 — after rebasing carver origins onto the local surface
     *   <li>0.119 — after zone-aware steering (vadose passages steepen toward vertical, so
     *       they breach far more often — the geology and the gameplay agree here)
     *   <li>0.203 — after ravines and sinkholes, which break the surface by construction
     *       instead of relying on a carver happening to breach
     *   <li>0.307 — after spaghetti channels replaced the single cheese field. Two noise
     *       sheets intersect in a curve, so tunnels connect over long distances instead of
     *       forming isolated pockets; this raised total carved volume AND its connected share
     * </ul>
     *
     * <p>Never 1.0: isolated pockets are a legitimate outcome of noise carving, and a region
     * this size clips tunnel systems at its own edges.
     */
    private static final double MIN_REACHABLE_FRACTION = 0.28;

    @Test
    public void carvedVolumeIsReachableFromTheSurface() {
        TerrainGenerationSystem terrain =
                new TerrainGenerationSystem(SEED, new DryHillsTileSource());

        int sizeX = REGION * CHUNK;
        int sizeZ = REGION * CHUNK;

        // Surface heights first, so the scan can stop just above the tallest column instead
        // of walking all 1024 blocks of a world whose caves all sit in the lowest third.
        int[] surface = new int[sizeX * sizeZ];
        int maxSurface = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int h = DryHillsTileSource.height(x, z);
                surface[x * sizeZ + z] = h;
                maxSurface = Math.max(maxSurface, h);
            }
        }
        int yCap = Math.min(maxSurface + 2, WorldConfiguration.WORLD_HEIGHT);

        boolean[] air = new boolean[sizeX * sizeZ * yCap];
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int x = cx * CHUNK + lx;
                        int z = cz * CHUNK + lz;
                        for (int y = 0; y < yCap; y++) {
                            if (chunk.getBlock(lx, y, lz) == BlockType.AIR) {
                                air[index(x, y, z, sizeZ, yCap)] = true;
                            }
                        }
                    }
                }
            }
        }

        // "Carved" = air that sits below its own column's surface. That is the volume a cave
        // system actually adds; air above the surface is just sky.
        int carved = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int top = Math.min(surface[x * sizeZ + z], yCap);
                for (int y = 1; y < top; y++) {
                    if (air[index(x, y, z, sizeZ, yCap)]) {
                        carved++;
                    }
                }
            }
        }
        assertTrue(carved > 0, "no carved volume at all — the carvers produced nothing");

        // Flood inward from open sky: every air cell at or above its column's surface.
        // The stack only ever holds air cells, so size it to those rather than to the whole
        // volume — at this region size the difference is tens of megabytes.
        int airCells = 0;
        for (boolean a : air) {
            if (a) airCells++;
        }
        boolean[] seen = new boolean[air.length];
        int[] stack = new int[airCells];
        int sp = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int from = Math.min(surface[x * sizeZ + z], yCap - 1);
                for (int y = from; y < yCap; y++) {
                    int i = index(x, y, z, sizeZ, yCap);
                    if (air[i] && !seen[i]) {
                        seen[i] = true;
                        stack[sp++] = i;
                    }
                }
            }
        }

        while (sp > 0) {
            int i = stack[--sp];
            int y = i % yCap;
            int rest = i / yCap;
            int z = rest % sizeZ;
            int x = rest / sizeZ;
            sp = push(air, seen, stack, sp, x - 1, y, z, sizeX, sizeZ, yCap);
            sp = push(air, seen, stack, sp, x + 1, y, z, sizeX, sizeZ, yCap);
            sp = push(air, seen, stack, sp, x, y - 1, z, sizeX, sizeZ, yCap);
            sp = push(air, seen, stack, sp, x, y + 1, z, sizeX, sizeZ, yCap);
            sp = push(air, seen, stack, sp, x, y, z - 1, sizeX, sizeZ, yCap);
            sp = push(air, seen, stack, sp, x, y, z + 1, sizeX, sizeZ, yCap);
        }

        int reached = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int top = Math.min(surface[x * sizeZ + z], yCap);
                for (int y = 1; y < top; y++) {
                    if (seen[index(x, y, z, sizeZ, yCap)]) {
                        reached++;
                    }
                }
            }
        }

        double fraction = reached / (double) carved;
        // Printed, not just asserted: this is a tracked metric with a ratchet floor, and the
        // value is the point. Reading it out of a failure message means only ever seeing it
        // when it is bad.
        System.out.printf("[caves] reachable from surface: %.2f%% (%d of %d carved blocks), "
                + "ratchet floor %.2f%%%n", fraction * 100, reached, carved,
                MIN_REACHABLE_FRACTION * 100);

        // The hard guard: this is the exact assertion that fails on the original bug.
        assertTrue(reached > 0, "not one carved block is reachable from the surface — the cave "
                + "network has no entrance (" + carved + " carved blocks, all sealed)");
        assertTrue(fraction >= MIN_REACHABLE_FRACTION, String.format(
                "only %.2f%% of carved volume is reachable from the surface (%d of %d); "
                        + "the ratchet floor is %.2f%%",
                fraction * 100, reached, carved, MIN_REACHABLE_FRACTION * 100));
    }

    private static int push(boolean[] air, boolean[] seen, int[] stack, int sp,
                            int x, int y, int z, int sizeX, int sizeZ, int yCap) {
        if (x < 0 || x >= sizeX || z < 0 || z >= sizeZ || y < 0 || y >= yCap) {
            return sp;
        }
        int i = index(x, y, z, sizeZ, yCap);
        if (air[i] && !seen[i]) {
            seen[i] = true;
            stack[sp++] = i;
        }
        return sp;
    }

    private static int index(int x, int y, int z, int sizeZ, int yCap) {
        return (x * sizeZ + z) * yCap + y;
    }
}
