package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.BitSet;
import java.util.Random;

/**
 * Generates large mid-to-lower-level caverns and their stalagmite/stalactite formations.
 *
 * Caverns are sparse compared to perlin-worm tunnels — only a small fraction of chunks
 * spawn one. Each cavern is a cluster of overlapping vertically-squashed ellipsoids
 * carved at low-mid Y, producing a roomy void rather than a ribbon corridor.
 *
 * After carving, a per-column formation pass scans the cavern's carved volume per
 * (x, z) column and stochastically places stone columns rising from the floor
 * (stalagmites) and descending from the ceiling (stalactites). Combined heights are
 * capped so a stalagmite/stalactite pair cannot bridge the cavern shut.
 *
 * {@link PerlinWormCarver} consults this class via {@link #nearestCavernChunk} and
 * {@link #computeCavernOrigin} so worm tunnels feed connectors into cavern centers.
 */
public final class CavernCarver {
    /** 1 in N chunks spawns a cavern. Higher => rarer caverns. */
    private static final int CAVERN_CHUNK_DIVISOR = 64;
    /** Cavern center Y range — mid-to-lower depths only. */
    private static final int CAVERN_Y_MIN = 10;
    private static final int CAVERN_Y_MAX = 30;

    /** Mean blob radius (blocks). Final radius per blob is BASE_RADIUS * [0.75 .. 1.20]. */
    private static final float BASE_RADIUS = 7f;
    /** Maximum horizontal blob offset from the cavern center. */
    private static final float BLOB_OFFSET = 5.5f;
    /** Y squash — caverns are wider than tall. */
    private static final float Y_SQUASH = 0.55f;
    private static final int MIN_BLOBS = 4;
    private static final int MAX_BLOBS = 7;

    /** Per-column probability of starting a stalagmite (stone column rising from floor). */
    private static final float STALAGMITE_CHANCE = 0.10f;
    /** Per-column probability of starting a stalactite (stone column descending from ceiling). */
    private static final float STALACTITE_CHANCE = 0.08f;
    /** Maximum height of any single formation pillar. */
    private static final int FORMATION_MAX_HEIGHT = 5;

    /** Worst-case carve reach from origin: max horizontal blob offset + max blob radius. */
    private static final float MAX_REACH = BLOB_OFFSET + BASE_RADIUS * 1.20f;
    /** Source-chunk scan radius (chunks), large enough to cover any blob reaching into the target chunk. */
    public static final int SCAN_RADIUS = (int) Math.ceil(MAX_REACH / WorldConfiguration.CHUNK_SIZE) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    /** Minimum surface-to-sea-level clearance for the cavern to carve a column — prevents underwater breach. */
    private static final int WATER_CLEARANCE = (int) Math.ceil(BASE_RADIUS * 1.20f) + 1;

    private final long seed;
    private final HeightMapGenerator heightMapGenerator;

    public CavernCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.heightMapGenerator = heightMapGenerator;
    }

    public boolean hasCavern(int cx, int cz) {
        long h = seed ^ 0xCA7EAA12CA7EAA12L;
        h ^= (long) cx * 0xBEA225F9EB34556DL;
        h = Long.rotateLeft(h, 19);
        h ^= (long) cz * 0x94D049BB133111EBL;
        return Math.floorMod(h, CAVERN_CHUNK_DIVISOR) == 0;
    }

    private long cavernRngSeed(int cx, int cz) {
        return ((seed * 6364136223846793005L) ^ ((long) cx * 0x9E3779B97F4A7C15L))
                ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
    }

    /**
     * Returns the cavern center (x, y, z) for a cavern-bearing chunk, or {@code null}
     * if the chunk does not host a cavern. Mirrors the first three RNG draws inside
     * {@link #carveCavern} exactly so worm connectors can predict the anchor without
     * having to re-walk the cavern.
     */
    public float[] computeCavernOrigin(int cx, int cz) {
        if (!hasCavern(cx, cz)) return null;
        Random rng = new Random(cavernRngSeed(cx, cz));
        float ox = cx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oz = cz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oy = CAVERN_Y_MIN + rng.nextInt(CAVERN_Y_MAX - CAVERN_Y_MIN);
        return new float[] { ox, oy, oz };
    }

    /**
     * Finds the nearest cavern-bearing chunk within {@code searchRadius} of (cx, cz).
     * Returns {@code null} if no cavern exists in range. Used by {@link PerlinWormCarver}
     * to direct connector tunnels into cavern centers.
     */
    public int[] nearestCavernChunk(int cx, int cz, int searchRadius) {
        int bestCx = 0, bestCz = 0;
        int bestDistSq = Integer.MAX_VALUE;
        boolean found = false;
        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
                int ncx = cx + dcx;
                int ncz = cz + dcz;
                if (!hasCavern(ncx, ncz)) continue;
                int d = dcx * dcx + dcz * dcz;
                if (d < bestDistSq) {
                    bestDistSq = d;
                    bestCx = ncx;
                    bestCz = ncz;
                    found = true;
                }
            }
        }
        return found ? new int[] { bestCx, bestCz } : null;
    }

    /**
     * Builds the cavern carve mask and formation (stalagmite/stalactite) mask for the
     * target chunk. Both bitsets use the packed local position {@code (x << 12) | (y << 4) | z}.
     * Caller must apply formationMask as STONE before applying carveMask as AIR.
     */
    public Result buildForChunk(int chunkX, int chunkZ, int[] targetHeights) {
        BitSet carve = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasCavern(srcCx, srcCz)) continue;
                carveCavern(srcCx, srcCz, chunkX, chunkZ, targetHeights, carve);
            }
        }
        BitSet formations = carve.isEmpty() ? new BitSet() : buildFormations(chunkX, chunkZ, carve);
        return new Result(carve, formations);
    }

    private void carveCavern(int srcCx, int srcCz, int targetCx, int targetCz,
                             int[] targetHeights, BitSet mask) {
        Random rng = new Random(cavernRngSeed(srcCx, srcCz));
        // First three draws must mirror computeCavernOrigin exactly.
        float ox = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oz = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oy = CAVERN_Y_MIN + rng.nextInt(CAVERN_Y_MAX - CAVERN_Y_MIN);

        int blobs = MIN_BLOBS + rng.nextInt(MAX_BLOBS - MIN_BLOBS + 1);
        for (int i = 0; i < blobs; i++) {
            float dx = (rng.nextFloat() - 0.5f) * 2f * BLOB_OFFSET;
            float dy = (rng.nextFloat() - 0.5f) * 2f * (BLOB_OFFSET * 0.45f);
            float dz = (rng.nextFloat() - 0.5f) * 2f * BLOB_OFFSET;
            float r = BASE_RADIUS * (0.75f + rng.nextFloat() * 0.45f);
            carveEllipsoid(ox + dx, oy + dy, oz + dz, r, targetCx, targetCz, targetHeights, mask);
        }
    }

    private void carveEllipsoid(float wx, float wy, float wz, float radius,
                                int targetCx, int targetCz, int[] targetHeights, BitSet mask) {
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        int rxz = (int) Math.ceil(radius);
        int ry = (int) Math.ceil(radius * Y_SQUASH);
        int wxi = Math.round(wx);
        int wyi = Math.round(wy);
        int wzi = Math.round(wz);
        if (wxi + rxz < targetBaseX || wxi - rxz >= targetBaseX + CHUNK_SIZE) return;
        if (wzi + rxz < targetBaseZ || wzi - rxz >= targetBaseZ + CHUNK_SIZE) return;

        float invRxz2 = 1f / (radius * radius);
        float ySpan = radius * Y_SQUASH;
        float invRy2 = 1f / (ySpan * ySpan);

        for (int ox = -rxz; ox <= rxz; ox++) {
            int bx = wxi + ox - targetBaseX;
            if (bx < 0 || bx >= CHUNK_SIZE) continue;
            for (int oz = -rxz; oz <= rxz; oz++) {
                int bz = wzi + oz - targetBaseZ;
                if (bz < 0 || bz >= CHUNK_SIZE) continue;
                int surface = targetHeights[bx * CHUNK_SIZE + bz];
                if (surface <= SEA_LEVEL + WATER_CLEARANCE) continue;
                float horizTerm = (ox * ox + oz * oz) * invRxz2;
                if (horizTerm >= 1f) continue;
                float maxOyTerm = 1f - horizTerm;
                for (int oy = -ry; oy <= ry; oy++) {
                    if ((oy * oy) * invRy2 >= maxOyTerm) continue;
                    int by = wyi + oy;
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    if (by >= surface) continue;
                    mask.set((bx << 12) | (by << 4) | bz);
                }
            }
        }
    }

    /**
     * Per-column scan of the cavern carve mask: for each (bx, bz) inside the target
     * chunk, find the lowest carved cell (floor) and highest carved cell (ceiling),
     * then stochastically place stone columns rising from the floor and descending
     * from the ceiling. Combined pillar heights are scaled down so they cannot meet.
     */
    private BitSet buildFormations(int chunkX, int chunkZ, BitSet carve) {
        BitSet formations = new BitSet();
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int bx = 0; bx < CHUNK_SIZE; bx++) {
            for (int bz = 0; bz < CHUNK_SIZE; bz++) {
                int floorY = -1;
                int ceilY = -1;
                for (int by = 1; by < WORLD_HEIGHT; by++) {
                    if (carve.get((bx << 12) | (by << 4) | bz)) {
                        if (floorY < 0) floorY = by;
                        ceilY = by;
                    }
                }
                int gap = ceilY - floorY;
                if (floorY < 0 || gap < 1) continue;

                int worldX = baseX + bx;
                int worldZ = baseZ + bz;
                Random rng = new Random(formationSeed(worldX, worldZ));

                int stalagH = rng.nextFloat() < STALAGMITE_CHANCE ? 1 + rng.nextInt(FORMATION_MAX_HEIGHT) : 0;
                int stalactiteH = rng.nextFloat() < STALACTITE_CHANCE ? 1 + rng.nextInt(FORMATION_MAX_HEIGHT) : 0;
                int total = stalagH + stalactiteH;
                if (total > gap && total > 0) {
                    // Scale both down proportionally so tips don't bridge the cavern.
                    stalagH = (int) ((long) stalagH * gap / total);
                    stalactiteH = (int) ((long) stalactiteH * gap / total);
                }

                // Stalagmite must rest on solid ground (cell directly below floor not carved).
                if (stalagH > 0 && floorY > 0 && !carve.get((bx << 12) | ((floorY - 1) << 4) | bz)) {
                    for (int h = 0; h < stalagH; h++) {
                        int by = floorY + h;
                        if (by > ceilY) break;
                        formations.set((bx << 12) | (by << 4) | bz);
                    }
                }
                // Stalactite must hang from solid ceiling (cell directly above ceiling not carved).
                if (stalactiteH > 0 && ceilY < WORLD_HEIGHT - 1
                        && !carve.get((bx << 12) | ((ceilY + 1) << 4) | bz)) {
                    for (int h = 0; h < stalactiteH; h++) {
                        int by = ceilY - h;
                        if (by < floorY) break;
                        formations.set((bx << 12) | (by << 4) | bz);
                    }
                }
            }
        }
        return formations;
    }

    private long formationSeed(int worldX, int worldZ) {
        long h = seed ^ 0x57A1AC1117EFCAFEL;
        h ^= (long) worldX * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 17);
        h ^= (long) worldZ * 0xC2B2AE3D27D4EB4FL;
        return h;
    }

    /** Bundled output of {@link #buildForChunk}. */
    public static final class Result {
        public final BitSet carveMask;
        public final BitSet formationMask;

        Result(BitSet carveMask, BitSet formationMask) {
            this.carveMask = carveMask;
            this.formationMask = formationMask;
        }
    }
}
