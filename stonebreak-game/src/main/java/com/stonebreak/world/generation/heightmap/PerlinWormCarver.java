package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.BitSet;
import java.util.Random;

/**
 * Walks Perlin worms that carve tunnels through terrain and breach the surface.
 *
 * Each chunk gets at most one worm, deterministically seeded from chunk coords.
 * Worms in surrounding chunks may carve into the target chunk, so generation
 * scans a fixed neighbourhood radius. Heading drifts using {@link NoiseGenerator#noise3D}
 * with a slight upward pitch bias, so a fraction of worms naturally exit the
 * surface and become cave entrances.
 */
public final class PerlinWormCarver {
    /** 1 in N source chunks spawns a worm. */
    private static final int WORM_CHUNK_DIVISOR = 6;
    /** Maximum step count per worm. STEP_SIZE * MAX_STEPS bounds the travel distance. */
    private static final int MAX_STEPS = 40;
    /** Distance per step in blocks. */
    private static final float STEP_SIZE = 1.2f;
    /** Carving radius in blocks (sphere around each step). */
    private static final float CARVE_RADIUS = 2.4f;
    /** Source-chunk scan radius (chunks). Must cover STEP_SIZE * MAX_STEPS / CHUNK_SIZE. */
    private static final int SCAN_RADIUS = 3;
    /** Heading-noise wavelength in blocks. */
    private static final float HEADING_SCALE = 1f / 30f;
    /** Per-step upward pitch nudge (radians). Lifts worms toward the surface over time. */
    private static final float UPWARD_BIAS = 0.12f;
    /** Pitch clamp. */
    private static final float PITCH_MIN = -0.6f;
    private static final float PITCH_MAX = 0.85f;
    /** Worm origin Y range. */
    private static final int ORIGIN_Y_MIN = 18;
    private static final int ORIGIN_Y_MAX = 48;
    /** Termination Y bounds. */
    private static final int Y_FLOOR = 6;
    /** Stop carving once well above the local surface — worm has fully breached. */
    private static final int BREACH_OVERHEAD = 3;
    /**
     * Minimum clearance between local surface and sea level for the worm to keep carving.
     * Prevents lateral carve-sphere leakage exposing water to the cavity (water flow is
     * expensive to simulate, so we never carve coastal / underwater terrain).
     */
    private static final int WATER_CLEARANCE = (int) Math.ceil(CARVE_RADIUS) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int CARVE_R = (int) Math.ceil(CARVE_RADIUS);
    private static final float CARVE_R2 = CARVE_RADIUS * CARVE_RADIUS;

    private final long seed;
    private final NoiseGenerator headingNoise;
    private final HeightMapGenerator heightMapGenerator;

    public PerlinWormCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.headingNoise = new NoiseGenerator(seed + 41, 1, 0.5, 2.0);
        this.heightMapGenerator = heightMapGenerator;
    }

    /**
     * Builds the carve mask for a chunk. Bits are packed local positions
     * {@code (x << 12) | (y << 4) | z}; set bits should be replaced with AIR
     * by the caller (only when the block would otherwise be solid).
     *
     * @param targetHeights the chunk's pre-computed per-column surface heights
     *                      (CHUNK_SIZE * CHUNK_SIZE, indexed [x*CHUNK_SIZE + z]).
     *                      Used to skip carving any column whose surface sits at
     *                      or near sea level — prevents exposing water to caves,
     *                      which would otherwise trigger continuous flow ticks.
     */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights) {
        BitSet mask = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasWorm(srcCx, srcCz)) {
                    continue;
                }
                walkWorm(srcCx, srcCz, chunkX, chunkZ, targetHeights, mask);
            }
        }
        return mask;
    }

    private boolean hasWorm(int cx, int cz) {
        long h = seed;
        h ^= (long) cx * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 23);
        h ^= (long) cz * 0xC2B2AE3D27D4EB4FL;
        return Math.floorMod(h, WORM_CHUNK_DIVISOR) == 0;
    }

    private void walkWorm(int srcCx, int srcCz, int targetCx, int targetCz, int[] targetHeights, BitSet mask) {
        Random rng = new Random(((seed * 6364136223846793005L) + srcCx) * 1442695040888963407L + srcCz);
        float x = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float z = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float y = ORIGIN_Y_MIN + rng.nextInt(ORIGIN_Y_MAX - ORIGIN_Y_MIN);
        float yaw = rng.nextFloat() * (float) (Math.PI * 2);
        float pitch = -0.2f + rng.nextFloat() * 0.4f;

        for (int step = 0; step < MAX_STEPS; step++) {
            float yawNoise = headingNoise.noise3D(x * HEADING_SCALE, y * HEADING_SCALE, z * HEADING_SCALE);
            float pitchNoise = headingNoise.noise3D((x + 1024f) * HEADING_SCALE, y * HEADING_SCALE, (z + 1024f) * HEADING_SCALE);
            yaw += yawNoise * 0.4f;
            pitch += pitchNoise * 0.25f + UPWARD_BIAS;
            if (pitch < PITCH_MIN) pitch = PITCH_MIN;
            else if (pitch > PITCH_MAX) pitch = PITCH_MAX;

            float cosPitch = (float) Math.cos(pitch);
            x += (float) Math.cos(yaw) * cosPitch * STEP_SIZE;
            y += (float) Math.sin(pitch) * STEP_SIZE;
            z += (float) Math.sin(yaw) * cosPitch * STEP_SIZE;

            int wxi = Math.round(x);
            int wyi = Math.round(y);
            int wzi = Math.round(z);
            if (wyi < Y_FLOOR || wyi >= WORLD_HEIGHT) {
                break;
            }
            int surface = heightMapGenerator.generateHeight(wxi, wzi);
            if (surface <= SEA_LEVEL + WATER_CLEARANCE) {
                break;
            }
            if (wyi > surface + BREACH_OVERHEAD) {
                break;
            }
            carveSphere(wxi, wyi, wzi, targetCx, targetCz, targetHeights, mask);
        }
    }

    private void carveSphere(int wx, int wy, int wz, int targetCx, int targetCz,
                             int[] targetHeights, BitSet mask) {
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        // Bounding-box reject: most worm steps are nowhere near the target chunk
        // (we walk the same worm for every chunk in SCAN_RADIUS). Cheap exit avoids
        // the 343-cell distance loop for the common case.
        if (wx + CARVE_R < targetBaseX || wx - CARVE_R >= targetBaseX + CHUNK_SIZE) return;
        if (wz + CARVE_R < targetBaseZ || wz - CARVE_R >= targetBaseZ + CHUNK_SIZE) return;
        for (int ox = -CARVE_R; ox <= CARVE_R; ox++) {
            int bx = wx + ox - targetBaseX;
            if (bx < 0 || bx >= CHUNK_SIZE) continue;
            for (int oz = -CARVE_R; oz <= CARVE_R; oz++) {
                int bz = wz + oz - targetBaseZ;
                if (bz < 0 || bz >= CHUNK_SIZE) continue;
                // Per-column water guard: any column whose surface sits at or near
                // sea level has water sitting on top of it. Carving solid blocks
                // beneath would expose that water and trigger flow ticks every gameloop.
                if (targetHeights[bx * CHUNK_SIZE + bz] <= SEA_LEVEL + 1) continue;
                int oxz2 = ox * ox + oz * oz;
                for (int oy = -CARVE_R; oy <= CARVE_R; oy++) {
                    if (oxz2 + oy * oy > CARVE_R2) continue;
                    int by = wy + oy;
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    mask.set((bx << 12) | (by << 4) | bz);
                }
            }
        }
    }
}
