package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Random;

/**
 * Walks long, smoothly-curving tunnel carvers that produce ribbon-like cave systems.
 *
 * Each spawning chunk emits a primary carver and (usually) a twin going the opposite
 * direction from the same origin point — so the spawn point sits in the middle of a
 * through-routed corridor rather than at a dead end. Mid-walk branches add side
 * passages. Cave-to-cave intersections emerge naturally where two long carvers cross,
 * not from any forced rendezvous, which keeps individual carvers from clustering and
 * over-carving entire regions.
 *
 * Carve volumes are vertically-squashed ellipsoids whose radius is modulated along
 * the path by an independent noise channel — tunnels pinch and bulge, giving caves
 * a natural cross-section instead of a uniform pipe.
 */
public final class PerlinWormCarver {
    /** 1 in N source chunks spawns a carver pair. Lower => denser cave network. */
    private static final int WORM_CHUNK_DIVISOR = 8;
    /** Steps per primary carver. STEP_SIZE * MAX_STEPS bounds reach. */
    private static final int MAX_STEPS = 60;
    /** Distance per step in blocks. */
    private static final float STEP_SIZE = 1.0f;

    /** Base carve radius (blocks); modulated along the path. */
    private static final float BASE_RADIUS = 2.2f;
    /** Radius modulation amplitude — tunnels pinch and widen along their length. */
    private static final float RADIUS_AMP = 0.9f;
    /** Floor on per-step radius so noise dips don't pinch tunnels closed. */
    private static final float MIN_RADIUS = 1.3f;
    /** Y-axis squash factor — caves are wider than they are tall. */
    private static final float Y_SQUASH = 0.65f;

    /**
     * Source-chunk scan radius (chunks). Must cover origin offset + path reach + carve
     * radius. With MAX_STEPS=60 + BRANCH_MAX_STEPS=22 + 16 (origin) + 3 (radius) ~= 101
     * blocks ≈ 6.3 chunks, so 6 covers the worst-case branch endpoint.
     */
    private static final int SCAN_RADIUS = 6;

    /** Heading-noise wavelength in blocks (lower frequency => smoother curves). */
    private static final float HEADING_SCALE = 1f / 38f;
    /** Radius-noise wavelength in blocks. */
    private static final float RADIUS_SCALE = 1f / 18f;
    /** Per-step yaw drift gain (radians per unit noise). */
    private static final float YAW_DRIFT = 0.18f;
    /** Per-step pitch drift gain. */
    private static final float PITCH_DRIFT = 0.10f;
    /** Mild upward pitch bias so a fraction of carvers eventually breach to surface. */
    private static final float UPWARD_BIAS = 0.025f;
    /** Pitch clamp. */
    private static final float PITCH_MIN = -0.85f;
    private static final float PITCH_MAX = 0.65f;

    /** Per-step probability of spawning a child branch. */
    private static final float BRANCH_CHANCE = 0.04f;
    /** Steps a branch carver gets — short side passages, not full secondary tunnels. */
    private static final int BRANCH_MAX_STEPS = 22;
    /** Maximum branches per root carver. Branches do not re-branch. */
    private static final int MAX_BRANCHES = 2;
    /** Skip branching for the first N steps so branches don't pile up at the spawn. */
    private static final int BRANCH_MIN_STEP = 5;
    /** Probability that a primary carver also spawns a paired sibling at the origin going the opposite way. */
    private static final float TWIN_CHANCE = 0.75f;

    /** Probability that a spawn chunk fires a connector carver toward its nearest worm-bearing neighbor. */
    private static final float CONNECTOR_CHANCE = 0.95f;
    /** Chunk radius to scan when searching for a connection target. */
    private static final int CONNECTOR_SEARCH_RADIUS = 4;
    /** Per-step lerp factor steering the connector toward its target (dominates noise drift). */
    private static final float CONNECTOR_BIAS = 0.55f;
    /** Step budget for a connector — enough to bridge {@link #CONNECTOR_SEARCH_RADIUS} chunks plus slack. */
    private static final int CONNECTOR_MAX_STEPS = 80;
    /**
     * Distance from target at which the connector terminates. Equal to {@link #BASE_RADIUS} so
     * the connector's last carved sphere overlaps the target chunk's primary/twin first-step
     * carve (which radiates outward from the same origin point), guaranteeing a tunnel link.
     */
    private static final float CONNECTOR_REACHED_DIST = BASE_RADIUS;

    /** Carver origin Y range. */
    private static final int ORIGIN_Y_MIN = 14;
    private static final int ORIGIN_Y_MAX = 50;
    /** Termination Y bounds. */
    private static final int Y_FLOOR = 6;
    /** Stop carving once well above the local surface — the carver has fully breached. */
    private static final int BREACH_OVERHEAD = 3;
    /**
     * Minimum clearance between local surface and sea level for the carver to keep going.
     * Computed from max possible carve radius so lateral ellipsoid leakage cannot expose
     * water (water flow ticks are expensive — never carve underwater terrain).
     */
    private static final int WATER_CLEARANCE = (int) Math.ceil(BASE_RADIUS + RADIUS_AMP) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    private final long seed;
    private final NoiseGenerator headingNoise;
    private final NoiseGenerator radiusNoise;
    private final HeightMapGenerator heightMapGenerator;

    public PerlinWormCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.headingNoise = new NoiseGenerator(seed + 41, 1, 0.5, 2.0);
        this.radiusNoise = new NoiseGenerator(seed + 113, 1, 0.5, 2.0);
        this.heightMapGenerator = heightMapGenerator;
    }

    /**
     * Builds the carve mask for a chunk. Bits are packed local positions
     * {@code (x << 12) | (y << 4) | z}; set bits should be replaced with AIR
     * by the caller (only when the block would otherwise be solid).
     */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights) {
        BitSet mask = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasWorm(srcCx, srcCz)) continue;
                spawnCarvers(srcCx, srcCz, chunkX, chunkZ, targetHeights, mask);
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

    private long chunkRngSeed(int cx, int cz) {
        return ((seed * 6364136223846793005L) + cx) * 1442695040888963407L + cz;
    }

    /**
     * Returns the deterministic origin (x, y, z) for a worm-bearing chunk. Must mirror
     * the first three RNG draws inside {@link #spawnCarvers} exactly so connectors can
     * predict where a neighbor's tunnel system anchors without re-walking it.
     */
    private float[] computeOrigin(int cx, int cz) {
        Random rng = new Random(chunkRngSeed(cx, cz));
        float ox = cx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oz = cz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oy = ORIGIN_Y_MIN + rng.nextInt(ORIGIN_Y_MAX - ORIGIN_Y_MIN);
        return new float[] { ox, oy, oz };
    }

    /**
     * Finds the nearest worm-bearing chunk within {@link #CONNECTOR_SEARCH_RADIUS} of
     * (cx, cz), excluding (cx, cz) itself. Returns {@code null} if no neighbor exists.
     */
    private int[] nearestWormChunk(int cx, int cz) {
        int bestCx = 0, bestCz = 0;
        int bestDistSq = Integer.MAX_VALUE;
        boolean found = false;
        for (int dcx = -CONNECTOR_SEARCH_RADIUS; dcx <= CONNECTOR_SEARCH_RADIUS; dcx++) {
            for (int dcz = -CONNECTOR_SEARCH_RADIUS; dcz <= CONNECTOR_SEARCH_RADIUS; dcz++) {
                if (dcx == 0 && dcz == 0) continue;
                int ncx = cx + dcx;
                int ncz = cz + dcz;
                if (!hasWorm(ncx, ncz)) continue;
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

    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        float twoPi = (float) (Math.PI * 2);
        while (diff > Math.PI) diff -= twoPi;
        while (diff < -Math.PI) diff += twoPi;
        return from + diff * t;
    }

    /**
     * Spawns the carver pair (primary + optional twin) for one source chunk and drains
     * the branch queue. Both members of the pair share an origin so any tunnel on one
     * side of the spawn point continues out the other side.
     */
    private void spawnCarvers(int srcCx, int srcCz, int targetCx, int targetCz,
                              int[] targetHeights, BitSet mask) {
        Random rng = new Random(chunkRngSeed(srcCx, srcCz));
        float ox = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oz = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oy = ORIGIN_Y_MIN + rng.nextInt(ORIGIN_Y_MAX - ORIGIN_Y_MIN);
        float yaw = rng.nextFloat() * (float) (Math.PI * 2);
        float pitch = -0.15f + rng.nextFloat() * 0.3f;
        boolean spawnTwin = rng.nextFloat() < TWIN_CHANCE;
        long primarySeed = rng.nextLong();
        long twinSeed = rng.nextLong();
        boolean spawnConnector = rng.nextFloat() < CONNECTOR_CHANCE;
        long connectorSeed = rng.nextLong();

        Deque<CarverSegment> queue = new ArrayDeque<>();
        queue.push(new CarverSegment(ox, oy, oz, yaw, pitch, MAX_STEPS, MAX_BRANCHES, primarySeed, null));
        if (spawnTwin) {
            // Mirror heading: opposite yaw, mirrored pitch — produces a continuous corridor
            // through the origin instead of a half-tunnel that ends at the spawn point.
            float twinYaw = yaw + (float) Math.PI;
            float twinPitch = -pitch;
            queue.push(new CarverSegment(ox, oy, oz, twinYaw, twinPitch, MAX_STEPS, MAX_BRANCHES, twinSeed, null));
        }
        if (spawnConnector) {
            queueConnector(srcCx, srcCz, ox, oy, oz, connectorSeed, queue);
        }

        while (!queue.isEmpty()) {
            walkCarver(queue.pop(), queue, targetCx, targetCz, targetHeights, mask);
        }
    }

    /**
     * Queues a connector carver from this chunk's origin to its nearest worm-bearing
     * neighbor's origin. The neighbor's primary/twin radiate from that same origin point,
     * so when the connector terminates at {@link #CONNECTOR_REACHED_DIST} of the target
     * its final carve sphere overlaps the neighbor's first carved blocks — producing a
     * guaranteed tunnel link rather than a probabilistic intersection.
     */
    private void queueConnector(int srcCx, int srcCz, float ox, float oy, float oz,
                                long connectorSeed, Deque<CarverSegment> queue) {
        int[] neighbor = nearestWormChunk(srcCx, srcCz);
        if (neighbor == null) return;
        float[] target = computeOrigin(neighbor[0], neighbor[1]);
        float dx = target[0] - ox;
        float dy = target[1] - oy;
        float dz = target[2] - oz;
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.atan2(dz, dx);
        float pitch = horiz > 0.001f ? (float) Math.atan2(dy, horiz) : 0f;
        // Clamp initial pitch so the connector doesn't immediately try to dive past PITCH_MIN.
        if (pitch < PITCH_MIN) pitch = PITCH_MIN;
        else if (pitch > PITCH_MAX) pitch = PITCH_MAX;
        queue.push(new CarverSegment(ox, oy, oz, yaw, pitch,
                CONNECTOR_MAX_STEPS, 0, connectorSeed, target));
    }

    private void walkCarver(CarverSegment seg, Deque<CarverSegment> queue,
                            int targetCx, int targetCz, int[] targetHeights, BitSet mask) {
        Random rng = new Random(seg.rngSeed);
        float x = seg.x, y = seg.y, z = seg.z, yaw = seg.yaw, pitch = seg.pitch;
        int branchesLeft = seg.branchesLeft;

        float reachedSq = CONNECTOR_REACHED_DIST * CONNECTOR_REACHED_DIST;

        for (int step = 0; step < seg.stepBudget; step++) {
            float yawNoise = headingNoise.noise3D(x * HEADING_SCALE, y * HEADING_SCALE, z * HEADING_SCALE);
            float pitchNoise = headingNoise.noise3D((x + 1024f) * HEADING_SCALE, y * HEADING_SCALE, (z + 1024f) * HEADING_SCALE);
            yaw += yawNoise * YAW_DRIFT;
            pitch += pitchNoise * PITCH_DRIFT + UPWARD_BIAS;

            if (seg.target != null) {
                float dx = seg.target[0] - x;
                float dy = seg.target[1] - y;
                float dz = seg.target[2] - z;
                float horiz = (float) Math.sqrt(dx * dx + dz * dz);
                if (horiz > 0.001f) {
                    float tgtYaw = (float) Math.atan2(dz, dx);
                    float tgtPitch = (float) Math.atan2(dy, horiz);
                    yaw = lerpAngle(yaw, tgtYaw, CONNECTOR_BIAS);
                    pitch += (tgtPitch - pitch) * CONNECTOR_BIAS;
                }
            }

            if (pitch < PITCH_MIN) pitch = PITCH_MIN;
            else if (pitch > PITCH_MAX) pitch = PITCH_MAX;

            float cosPitch = (float) Math.cos(pitch);
            x += (float) Math.cos(yaw) * cosPitch * STEP_SIZE;
            y += (float) Math.sin(pitch) * STEP_SIZE;
            z += (float) Math.sin(yaw) * cosPitch * STEP_SIZE;

            int wxi = Math.round(x);
            int wyi = Math.round(y);
            int wzi = Math.round(z);
            if (wyi < Y_FLOOR || wyi >= WORLD_HEIGHT) break;
            int surface = heightMapGenerator.generateHeight(wxi, wzi);
            if (surface <= SEA_LEVEL + WATER_CLEARANCE) break;
            if (wyi > surface + BREACH_OVERHEAD) break;

            float radius = BASE_RADIUS + radiusNoise.noise3D(x * RADIUS_SCALE, y * RADIUS_SCALE, z * RADIUS_SCALE) * RADIUS_AMP;
            if (radius < MIN_RADIUS) radius = MIN_RADIUS;
            carveEllipsoid(wxi, wyi, wzi, radius, targetCx, targetCz, targetHeights, mask);

            if (seg.target != null) {
                float dx = seg.target[0] - x;
                float dy = seg.target[1] - y;
                float dz = seg.target[2] - z;
                if (dx * dx + dy * dy + dz * dz < reachedSq) {
                    // Arrived. The neighbor's primary/twin radiates from this same point,
                    // so terminating here keeps the link clean rather than over-carving a hub.
                    break;
                }
            }

            if (branchesLeft > 0 && step >= BRANCH_MIN_STEP && rng.nextFloat() < BRANCH_CHANCE) {
                branchesLeft--;
                // Side passage offset roughly perpendicular to current heading, with jitter.
                float branchOffset = (rng.nextBoolean() ? 1.1f : -1.1f) + (rng.nextFloat() - 0.5f) * 0.6f;
                float branchYaw = yaw + branchOffset;
                float branchPitch = pitch + (rng.nextFloat() - 0.5f) * 0.3f;
                queue.push(new CarverSegment(x, y, z, branchYaw, branchPitch,
                        BRANCH_MAX_STEPS, 0, rng.nextLong(), null));
            }
        }
    }

    /**
     * Carves a vertically-squashed ellipsoid into the target chunk's mask. The
     * Y-axis squash gives tunnels their characteristic flatter cross-section.
     * Per-column water guard prevents exposing water-bearing terrain.
     */
    private void carveEllipsoid(int wx, int wy, int wz, float radius,
                                int targetCx, int targetCz, int[] targetHeights, BitSet mask) {
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        int rxz = (int) Math.ceil(radius);
        int ry = (int) Math.ceil(radius * Y_SQUASH);
        // Bounding-box reject: most carver steps are nowhere near the target chunk.
        if (wx + rxz < targetBaseX || wx - rxz >= targetBaseX + CHUNK_SIZE) return;
        if (wz + rxz < targetBaseZ || wz - rxz >= targetBaseZ + CHUNK_SIZE) return;

        float invRxz2 = 1f / (radius * radius);
        float ySpan = radius * Y_SQUASH;
        float invRy2 = 1f / (ySpan * ySpan);

        for (int ox = -rxz; ox <= rxz; ox++) {
            int bx = wx + ox - targetBaseX;
            if (bx < 0 || bx >= CHUNK_SIZE) continue;
            for (int oz = -rxz; oz <= rxz; oz++) {
                int bz = wz + oz - targetBaseZ;
                if (bz < 0 || bz >= CHUNK_SIZE) continue;
                if (targetHeights[bx * CHUNK_SIZE + bz] <= SEA_LEVEL + 1) continue;
                float horizTerm = (ox * ox + oz * oz) * invRxz2;
                if (horizTerm >= 1f) continue;
                float maxOyTerm = 1f - horizTerm;
                for (int oy = -ry; oy <= ry; oy++) {
                    if ((oy * oy) * invRy2 >= maxOyTerm) continue;
                    int by = wy + oy;
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    mask.set((bx << 12) | (by << 4) | bz);
                }
            }
        }
    }

    private static final class CarverSegment {
        final float x, y, z, yaw, pitch;
        final int stepBudget;
        final int branchesLeft;
        final long rngSeed;
        /** Non-null only for connector segments — when set, carver steers toward this point and terminates near it. */
        final float[] target;

        CarverSegment(float x, float y, float z, float yaw, float pitch,
                      int stepBudget, int branchesLeft, long rngSeed, float[] target) {
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.stepBudget = stepBudget;
            this.branchesLeft = branchesLeft;
            this.rngSeed = rngSeed;
            this.target = target;
        }
    }
}
