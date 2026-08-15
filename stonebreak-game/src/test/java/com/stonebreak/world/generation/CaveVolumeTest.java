package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caves must be big enough to play in.
 *
 * <p>{@link CaveReachabilityTest} asks whether carved volume can be reached and
 * {@link CaveStoreyStructureTest} asks whether it is arranged into storeys. Neither says
 * anything about its <em>size</em>, and a cave system can pass both while consisting entirely
 * of one-block slots a player cannot walk down.
 *
 * <p>Carved volume alone does not say it either, which is the trap this test exists to avoid:
 * every cheap way to raise the block count — thinner noise thresholds, more spawn points —
 * adds crawlways, and a generator tuned against volume drifts toward a sponge of them. So the
 * ratchet is on two quantities that can only both rise if the passages themselves got bigger:
 *
 * <ul>
 *   <li><b>volume</b> — carved share of all sub-surface rock
 *   <li><b>roominess</b> — the share of carved volume whose clearance (distance from the
 *       nearest solid block, by chamfer distance transform) is at least
 *       {@link #ROOMY_CLEARANCE}, i.e. that sits in a space roughly four blocks across or
 *       more. Splitting the same volume into narrower tunnels drives this down.
 * </ul>
 *
 * <p>Stand spots — floor with player headroom above it — are reported alongside as the
 * plainest reading of "somewhere to be", but are not ratcheted: they track roominess closely
 * enough that a second floor on the same property would only be noise.
 */
public class CaveVolumeTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int REGION = 8;

    /** Air blocks a column needs above a floor for the player to stand there. */
    private static final int PLAYER_HEIGHT = 2;

    /** Clearance (blocks from the nearest solid) at which a passage counts as roomy. */
    private static final double ROOMY_CLEARANCE = 2.0;

    /**
     * Ratchet floors. <b>Raise these as caves improve; never lower them.</b> History
     * (measured values, floors set just under them):
     *
     * <ul>
     *   <li>0.065 volume / 0.32 roomy — the tuning reported as too cramped to play in: worm
     *       bores ~2.9 blocks tall, caverns barely wider than the tunnels feeding them, and
     *       spaghetti tubes a block or two across
     *   <li>0.102 volume / 0.44 roomy — after widening the worm bore and the cavern blobs,
     *       and stretching the spaghetti wavelength so the same carved share is spent on
     *       fewer, larger passages instead of more crawlways
     * </ul>
     */
    private static final double MIN_CARVED_FRACTION = 0.098;
    private static final double MIN_ROOMY_FRACTION = 0.41;

    @Test
    public void cavesAreLargeEnoughToPlayIn() {
        TerrainGenerationSystem terrain =
                new TerrainGenerationSystem(SEED, new DryHillsTileSource());

        int sizeX = REGION * CHUNK;
        int sizeZ = REGION * CHUNK;
        int[] surface = new int[sizeX * sizeZ];
        int maxSurface = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int h = DryHillsTileSource.height(x, z);
                surface[x * sizeZ + z] = h;
                maxSurface = Math.max(maxSurface, h);
            }
        }
        int yCap = Math.min(maxSurface, WorldConfiguration.WORLD_HEIGHT);

        boolean[] air = new boolean[sizeX * sizeZ * yCap];
        long standSpots = 0;
        long openColumns = 0;
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                Chunk chunk = terrain.generateTerrainOnly(cx, cz).chunk();
                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        int x = cx * CHUNK + lx;
                        int z = cz * CHUNK + lz;
                        int top = Math.min(surface[x * sizeZ + z], yCap);
                        if (chunk.getBlock(lx, top - 1, lz) == BlockType.AIR) {
                            openColumns++;   // a cave broke the ground open in this column
                        }
                        for (int y = 1; y < top; y++) {
                            if (chunk.getBlock(lx, y, lz) != BlockType.AIR) {
                                continue;
                            }
                            air[index(x, y, z, sizeZ, yCap)] = true;
                            boolean floor = chunk.getBlock(lx, y - 1, lz) != BlockType.AIR;
                            boolean headroom = true;
                            for (int h = 1; h < PLAYER_HEIGHT && headroom; h++) {
                                headroom = chunk.getBlock(lx, y + h, lz) == BlockType.AIR;
                            }
                            if (floor && headroom) {
                                standSpots++;
                            }
                        }
                    }
                }
            }
        }

        float[] clearance = clearanceField(air, sizeX, sizeZ, yCap);

        long carved = 0;
        long subsurface = 0;
        long roomy = 0;
        double clearanceSum = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int top = Math.min(surface[x * sizeZ + z], yCap);
                for (int y = 1; y < top; y++) {
                    subsurface++;
                    int i = index(x, y, z, sizeZ, yCap);
                    if (!air[i]) {
                        continue;
                    }
                    carved++;
                    clearanceSum += clearance[i];
                    if (clearance[i] >= ROOMY_CLEARANCE) {
                        roomy++;
                    }
                }
            }
        }

        double fraction = carved / (double) subsurface;
        double roomyFraction = roomy / (double) carved;
        double meanClearance = clearanceSum / carved;
        double perChunk = standSpots / (double) (REGION * REGION);

        // Surface openings are reported, not ratcheted: they are the cost side of this
        // tuning. Bigger caves that breach more often are wanted; a landscape pitted with
        // holes is not, and only this number distinguishes the two.
        double openPercent = openColumns * 100.0 / (sizeX * (double) sizeZ);

        System.out.printf("[caves] volume: %.3f%% of sub-surface rock (%d of %d); roomy: "
                        + "%.1f%% of carved volume; mean clearance %.2f blocks; "
                        + "stand spots %.0f per chunk; surface columns open to a cave %.2f%%%n",
                fraction * 100, carved, subsurface, roomyFraction * 100, meanClearance, perChunk,
                openPercent);

        assertTrue(fraction >= MIN_CARVED_FRACTION, String.format(
                "carved volume is %.3f%% of sub-surface rock; the ratchet floor is %.3f%%",
                fraction * 100, MIN_CARVED_FRACTION * 100));
        assertTrue(roomyFraction >= MIN_ROOMY_FRACTION, String.format(
                "only %.1f%% of carved volume has %.1f blocks of clearance (need %.1f%%) — "
                        + "the caves have volume but it is spent on crawlways",
                roomyFraction * 100, ROOMY_CLEARANCE, MIN_ROOMY_FRACTION * 100));
    }

    /**
     * Distance from every air cell to the nearest solid block, by two-pass chamfer transform
     * with 1 / sqrt(2) / sqrt(3) step weights. Cells outside the region count as solid, which
     * understates clearance at the region's own faces — uniformly, so the measure stays
     * comparable between runs.
     */
    private static float[] clearanceField(boolean[] air, int sizeX, int sizeZ, int yCap) {
        float[] d = new float[air.length];
        for (int i = 0; i < air.length; i++) {
            d[i] = air[i] ? Float.MAX_VALUE : 0f;
        }
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < yCap; y++) {
                    relax(d, air, x, y, z, sizeX, sizeZ, yCap, -1);
                }
            }
        }
        for (int x = sizeX - 1; x >= 0; x--) {
            for (int z = sizeZ - 1; z >= 0; z--) {
                for (int y = yCap - 1; y >= 0; y--) {
                    relax(d, air, x, y, z, sizeX, sizeZ, yCap, 1);
                }
            }
        }
        return d;
    }

    /** One chamfer relaxation over the half-neighbourhood on {@code dir}'s side. */
    private static void relax(float[] d, boolean[] air, int x, int y, int z,
                              int sizeX, int sizeZ, int yCap, int dir) {
        int i = index(x, y, z, sizeZ, yCap);
        if (!air[i]) {
            return;
        }
        float best = d[i];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    // Only the neighbours already finalised by this sweep's direction.
                    int order = dx != 0 ? dx : (dz != 0 ? dz : dy);
                    if (order != dir || (dx == 0 && dz == 0 && dy == 0)) {
                        continue;
                    }
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    float step = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (nx < 0 || nx >= sizeX || nz < 0 || nz >= sizeZ || ny < 0 || ny >= yCap) {
                        best = Math.min(best, step);   // outside the region reads as solid
                        continue;
                    }
                    float n = d[index(nx, ny, nz, sizeZ, yCap)];
                    if (n + step < best) {
                        best = n + step;
                    }
                }
            }
        }
        d[i] = best;
    }

    private static int index(int x, int y, int z, int sizeZ, int yCap) {
        return (x * sizeZ + z) * yCap + y;
    }
}
