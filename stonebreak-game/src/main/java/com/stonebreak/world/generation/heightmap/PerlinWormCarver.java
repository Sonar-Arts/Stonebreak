package com.stonebreak.world.generation.heightmap;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.diffusion.TerrainTile;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Random;

/**
 * Walks long, smoothly-curving tunnel carvers that produce ribbon-like cave systems.
 *
 * <p>Each spawning chunk emits a primary carver and (usually) a twin going the opposite
 * direction from the same origin point — so the spawn point sits in the middle of a
 * through-routed corridor rather than at a dead end. Mid-walk branches add side
 * passages. Cave-to-cave intersections emerge naturally where two long carvers cross,
 * not from any forced rendezvous, which keeps individual carvers from clustering and
 * over-carving entire regions.
 *
 * <p>Carve volumes are vertically-squashed ellipsoids whose radius is modulated along
 * the path by an independent noise channel — tunnels pinch and bulge, giving caves
 * a natural cross-section instead of a uniform pipe.
 */
public final class PerlinWormCarver {
    /** 1 in N source chunks spawns a carver pair. Lower => denser cave network. */
    private static final int WORM_CHUNK_DIVISOR = 8;
    /**
     * Steps per primary carver. STEP_SIZE * MAX_STEPS bounds reach.
     *
     * <p>Raised from 60: with the origin now placed relative to the local surface rather
     * than to sea level, a tunnel has to cross a much taller underground column to reach
     * anything. Note the cost is quadratic — {@link #SCAN_RADIUS} grows with this, and the
     * per-chunk source scan is O(SCAN_RADIUS^2).
     */
    private static final int MAX_STEPS = 90;
    /** Distance per step in blocks. */
    private static final float STEP_SIZE = 1.0f;

    /**
     * Base carve radius (blocks); modulated along the path.
     *
     * <p>Sized for gameplay rather than for realism. At the old 2.2 the tunnel bore was
     * ~4 blocks across but only {@code 2 * 2.2 * Y_SQUASH} ≈ 2.9 blocks tall before the
     * noise dips — a corridor a player fits through and nothing more, which is what made the
     * cave network read as cramped everywhere the larger features did not reach. Worms are
     * the connective tissue of the whole system, so their bore sets the felt size of most of
     * it.
     */
    private static final float BASE_RADIUS = 3.1f;
    /** Radius modulation amplitude — tunnels pinch and widen along their length. */
    private static final float RADIUS_AMP = 1.25f;
    /** Floor on per-step radius so noise dips don't pinch tunnels closed. */
    private static final float MIN_RADIUS = 1.9f;
    /**
     * Y-axis squash factor — caves are wider than they are tall. This multiplies the bore's
     * vertical half-extent directly, so it is the cheapest headroom in the generator: at the
     * old 0.65 a mean-radius tunnel stood under three blocks tall, which is a corridor with
     * no room to jump, fight or place a block overhead.
     */
    private static final float Y_SQUASH = 0.78f;

    /**
     * Source-chunk scan radius (chunks). Must cover origin offset + path reach + carve
     * radius. With MAX_STEPS=90 + BRANCH_MAX_STEPS=22 + 16 (origin) + 5 (radius) ~= 133
     * blocks ≈ 8.4 chunks, so 9 covers the worst-case branch endpoint.
     *
     * <p>Kept a literal rather than derived from those constants because they are declared
     * below this point and a forward reference in the initializer will not compile. If you
     * change MAX_STEPS or BRANCH_MAX_STEPS, redo this arithmetic.
     */
    private static final int SCAN_RADIUS = 9;

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

    // --- Zone-aware steering (see CaveWaterTable) -------------------------------------
    // Passage shape should tell the player how deep they are. The three hydrological zones
    // produce visibly different tunnels from the same walk by biasing pitch differently.

    /**
     * Vadose: above the water table, where water descends under gravity and cuts steep
     * shafts and narrow canyons. Implemented by pushing pitch AWAY from horizontal rather
     * than downward — a vadose shaft is near-vertical in both directions, and the upward
     * half is what reaches the surface, which is exactly where such a passage starts in
     * reality (a sinkhole taking water in).
     */
    private static final float VADOSE_STEEPEN = 0.06f;
    private static final float VADOSE_PITCH_MIN = -0.95f;
    private static final float VADOSE_PITCH_MAX = 0.95f;

    /**
     * Epiphreatic: at a (present or former) water table, where flow is lateral and cuts
     * level galleries. Pitch is damped toward horizontal each step and clamped hard, so a
     * tunnel entering this band tracks it instead of passing through.
     */
    private static final float GALLERY_DAMP = 0.55f;
    private static final float GALLERY_PITCH_MIN = -0.14f;
    private static final float GALLERY_PITCH_MAX = 0.14f;

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
    /** Probability that a spawn chunk also fires a connector carver toward its nearest cavern. */
    private static final float CAVERN_CONNECTOR_CHANCE = 1.0f;
    /** Chunk radius to scan when searching for a connection target. */
    private static final int CONNECTOR_SEARCH_RADIUS = 4;
    /** Chunk radius to scan when searching for a cavern to feed into. */
    private static final int CAVERN_CONNECTOR_RADIUS = 5;
    /** Step budget for a cavern connector — caverns may sit further out than worm-chunk neighbors. */
    private static final int CAVERN_CONNECTOR_MAX_STEPS = 110;
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

    /**
     * Carver origin depth below the LOCAL surface, in blocks.
     *
     * <p>This used to be an absolute Y band expressed as a fraction of
     * {@link WorldConfiguration#SEA_LEVEL} (the old 14-50 band's share of the old 64-block
     * sea level). That was wrong, and by a wide margin: with SEA_LEVEL=320 it put every worm
     * between y=70 and y=250, while land surfaces sit at 340-500. A carver climbs at most
     * {@code MAX_STEPS * sin(PITCH_MAX)} blocks, so it could never reach the surface — the
     * entire worm/cavern network was sealed off with no entrances anywhere in the world.
     *
     * <p>Depth is relative to the surface above the origin because that is the quantity that
     * actually matters: "60 blocks down from where you are standing" is meaningful at any
     * terrain height, whereas "y=180" is near-surface in one world and unreachable in another.
     */
    private static final int ORIGIN_DEPTH_MIN = 20;
    private static final int ORIGIN_DEPTH_MAX = 140;
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

    private final long seed;
    private final NoiseGenerator headingNoise;
    private final NoiseGenerator radiusNoise;
    private final HeightMapGenerator heightMapGenerator;
    private final CaveWaterTable waterTable;
    private CavernCarver cavernCarver;
    private MegaCavernCarver megaCavernCarver;

    public PerlinWormCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.headingNoise = new NoiseGenerator(seed + 41, 1, 0.5, 2.0);
        this.radiusNoise = new NoiseGenerator(seed + 113, 1, 0.5, 2.0);
        this.heightMapGenerator = heightMapGenerator;
        this.waterTable = new CaveWaterTable(seed, heightMapGenerator);
    }

    /**
     * Wires in the cavern carver so worm-bearing chunks can fire connector tunnels
     * toward nearby caverns. Optional — without it, worms still link to other worms.
     */
    public void setCavernCarver(CavernCarver cavernCarver) {
        this.cavernCarver = cavernCarver;
    }

    /**
     * Wires in the megacavern carver so worm-bearing chunks can also fire connectors
     * toward nearby megacaverns. When both a normal cavern and a megacavern are in
     * range, the connector targets whichever anchor is geometrically closer.
     */
    public void setMegaCavernCarver(MegaCavernCarver megaCavernCarver) {
        this.megaCavernCarver = megaCavernCarver;
    }

    /** Scan radius in chunks — exported for the native carver's anchor precompute. */
    public static int scanRadius() {
        return SCAN_RADIUS;
    }

    /** Whether a source chunk spawns a carver pair (pure hash — native-carver parity). */
    public boolean hasWormAt(int cx, int cz) {
        return hasWorm(cx, cz);
    }

    /**
     * The cavern-connector anchor for a worm-bearing source chunk, or null when
     * no cavern/megacavern is in range. The native carver receives these
     * precomputed so cavern placement stays owned by the Java cavern carvers.
     */
    public float[] cavernAnchorFor(int cx, int cz) {
        float[] origin = computeOrigin(cx, cz);
        return nearestCavernAnchor(cx, cz, origin[0], origin[1], origin[2]);
    }

    /**
     * Builds the carve mask for a chunk. Bits use {@link LocalBlockKey#pack(int,int,int)} packed local
     * positions; set bits should be replaced with AIR
     * by the caller (only when the block would otherwise be solid).
     */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights) {
        return carveMaskForChunk(chunkX, chunkZ, targetHeights, null);
    }

    /**
     * As {@link #carveMaskForChunk(int, int, int[])}, keeping clear of the beds and bank
     * walls of wet columns — see {@link WaterGuard}. A null {@code waterLevels}
     * suppresses nothing.
     */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights, int[] waterLevels) {
        int[] waterGuard = WaterGuard.guardPlane(targetHeights, waterLevels, heightMapGenerator, chunkX, chunkZ);
        BitSet mask = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasWorm(srcCx, srcCz)) continue;
                spawnCarvers(srcCx, srcCz, chunkX, chunkZ, targetHeights, waterGuard, mask);
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
     * Origin Y for a carver spawning at {@code (ox, oz)} — a depth below that column's own
     * surface, not an absolute band.
     *
     * <p>Consumes exactly one {@code nextInt} so the RNG stream downstream (twin, connector
     * and cavern-connector seeds) is unchanged in shape. That matters because
     * {@link #computeOrigin} must mirror {@link #spawnCarvers}' first three draws exactly for
     * the predictable-anchor trick to keep producing guaranteed tunnel links — both call here.
     */
    private float originY(float ox, float oz, Random rng) {
        int surface = heightMapGenerator.generateHeight(Math.round(ox), Math.round(oz));
        int depth = ORIGIN_DEPTH_MIN + rng.nextInt(ORIGIN_DEPTH_MAX - ORIGIN_DEPTH_MIN);
        return Math.max(Y_FLOOR + 2, surface - depth);
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
        float oy = originY(ox, oz, rng);
        return new float[] { ox, oy, oz };
    }

    /**
     * Origin {@code (x, y, z)} of the nearest worm within {@code searchRadius} chunks of
     * (cx, cz), including (cx, cz) itself, or {@code null} if none is in range.
     *
     * <p>Exposed for {@link SinkholeCarver}, which cuts its shaft to exactly the depth that
     * opens into a tunnel. This is the same predictable-anchor trick the connectors use: the
     * spawn RNG stream is replayed far enough to recover the origin, so the answer costs
     * three RNG draws and a height lookup instead of walking the carver.
     */
    public float[] nearestWormOrigin(int cx, int cz, int searchRadius) {
        int bestCx = 0, bestCz = 0;
        int bestDistSq = Integer.MAX_VALUE;
        boolean found = false;
        for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
            for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
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
        return found ? computeOrigin(bestCx, bestCz) : null;
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
                              int[] targetHeights, int[] waterGuard, BitSet mask) {
        Random rng = new Random(chunkRngSeed(srcCx, srcCz));
        float ox = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oz = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float oy = originY(ox, oz, rng);
        float yaw = rng.nextFloat() * (float) (Math.PI * 2);
        float pitch = -0.15f + rng.nextFloat() * 0.3f;
        boolean spawnTwin = rng.nextFloat() < TWIN_CHANCE;
        long primarySeed = rng.nextLong();
        long twinSeed = rng.nextLong();
        boolean spawnConnector = rng.nextFloat() < CONNECTOR_CHANCE;
        long connectorSeed = rng.nextLong();
        boolean spawnCavernConnector = rng.nextFloat() < CAVERN_CONNECTOR_CHANCE;
        long cavernConnectorSeed = rng.nextLong();

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
        if (spawnCavernConnector) {
            queueCavernConnector(srcCx, srcCz, ox, oy, oz, cavernConnectorSeed, queue);
        }

        while (!queue.isEmpty()) {
            walkCarver(queue.pop(), queue, targetCx, targetCz, targetHeights, waterGuard, mask);
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

    /**
     * Queues a connector carver from this worm's origin to the nearest cavern center
     * within {@link #CAVERN_CONNECTOR_RADIUS}. Cavern blob radius easily eclipses
     * {@link #CONNECTOR_REACHED_DIST}, so terminating the connector that close to the
     * cavern origin guarantees the tunnel breaks into the cavern rather than stopping
     * just shy of the wall.
     */
    private void queueCavernConnector(int srcCx, int srcCz, float ox, float oy, float oz,
                                      long connectorSeed, Deque<CarverSegment> queue) {
        float[] target = nearestCavernAnchor(srcCx, srcCz, ox, oy, oz);
        if (target == null) return;
        float dx = target[0] - ox;
        float dy = target[1] - oy;
        float dz = target[2] - oz;
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.atan2(dz, dx);
        float pitch = horiz > 0.001f ? (float) Math.atan2(dy, horiz) : 0f;
        if (pitch < PITCH_MIN) pitch = PITCH_MIN;
        else if (pitch > PITCH_MAX) pitch = PITCH_MAX;
        queue.push(new CarverSegment(ox, oy, oz, yaw, pitch,
                CAVERN_CONNECTOR_MAX_STEPS, 0, connectorSeed, target));
    }

    /**
     * Returns the closer of (nearest normal cavern anchor, nearest megacavern anchor)
     * within {@link #CAVERN_CONNECTOR_RADIUS} of the worm origin, or {@code null} if
     * neither is in range. Distance is measured in world space from the worm origin
     * so connector length — not just chunk-grid distance — drives the choice.
     */
    private float[] nearestCavernAnchor(int srcCx, int srcCz, float ox, float oy, float oz) {
        float[] best = null;
        float bestDistSq = Float.MAX_VALUE;
        if (cavernCarver != null) {
            int[] neighbor = cavernCarver.nearestCavernChunk(srcCx, srcCz, CAVERN_CONNECTOR_RADIUS);
            if (neighbor != null) {
                float[] anchor = cavernCarver.computeCavernOrigin(neighbor[0], neighbor[1]);
                if (anchor != null) {
                    float d = distSq(anchor, ox, oy, oz);
                    if (d < bestDistSq) { bestDistSq = d; best = anchor; }
                }
            }
        }
        if (megaCavernCarver != null) {
            int[] neighbor = megaCavernCarver.nearestCavernChunk(srcCx, srcCz, CAVERN_CONNECTOR_RADIUS);
            if (neighbor != null) {
                float[] anchor = megaCavernCarver.computeCavernOrigin(neighbor[0], neighbor[1]);
                if (anchor != null) {
                    float d = distSq(anchor, ox, oy, oz);
                    if (d < bestDistSq) { bestDistSq = d; best = anchor; }
                }
            }
        }
        return best;
    }

    private static float distSq(float[] p, float ox, float oy, float oz) {
        float dx = p[0] - ox;
        float dy = p[1] - oy;
        float dz = p[2] - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void walkCarver(CarverSegment seg, Deque<CarverSegment> queue,
                            int targetCx, int targetCz, int[] targetHeights, int[] waterGuard,
                            BitSet mask) {
        Random rng = new Random(seg.rngSeed);
        float x = seg.x, y = seg.y, z = seg.z, yaw = seg.yaw, pitch = seg.pitch;
        int branchesLeft = seg.branchesLeft;

        float reachedSq = CONNECTOR_REACHED_DIST * CONNECTOR_REACHED_DIST;

        // Zone of the previous step's position. One step of lag is deliberate: the surface
        // and water level needed to resolve it are fetched AFTER the move (they describe
        // where the carver landed), and re-fetching them here to remove the lag would
        // triple this loop's tile lookups for a steering heuristic. Starts PHREATIC so a
        // carver's first step behaves as it always did.
        CaveWaterTable.Zone zone = CaveWaterTable.Zone.PHREATIC;

        for (int step = 0; step < seg.stepBudget; step++) {
            float yawNoise = headingNoise.noise3D(x * HEADING_SCALE, y * HEADING_SCALE, z * HEADING_SCALE);
            float pitchNoise = headingNoise.noise3D((x + 1024f) * HEADING_SCALE, y * HEADING_SCALE, (z + 1024f) * HEADING_SCALE);
            yaw += yawNoise * YAW_DRIFT;
            pitch += pitchNoise * PITCH_DRIFT + UPWARD_BIAS;

            // Shape the passage to its zone. Connectors are exempt: their whole job is to
            // reach a specific anchor, and a gallery clamp would stop them ever climbing or
            // diving to it, breaking the guaranteed-link property.
            if (seg.target == null) {
                switch (zone) {
                    case VADOSE ->
                        // Away from horizontal, preserving sign — steep in whichever
                        // direction the carver is already going.
                        pitch += Math.signum(pitch) * VADOSE_STEEPEN;
                    case EPIPHREATIC ->
                        pitch *= GALLERY_DAMP;
                    case PHREATIC -> {
                        // Free wander — rounded tubes that rise and fall. Unchanged.
                    }
                }
            }

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

            // Clamp to the zone's envelope: steeper than default in the vadose zone,
            // near-flat in a gallery. Connectors keep the default so they can still aim.
            float pitchMin = PITCH_MIN;
            float pitchMax = PITCH_MAX;
            if (seg.target == null) {
                switch (zone) {
                    case VADOSE -> { pitchMin = VADOSE_PITCH_MIN; pitchMax = VADOSE_PITCH_MAX; }
                    case EPIPHREATIC -> { pitchMin = GALLERY_PITCH_MIN; pitchMax = GALLERY_PITCH_MAX; }
                    case PHREATIC -> { }
                }
            }
            if (pitch < pitchMin) pitch = pitchMin;
            else if (pitch > pitchMax) pitch = pitchMax;

            float cosPitch = (float) Math.cos(pitch);
            x += (float) Math.cos(yaw) * cosPitch * STEP_SIZE;
            y += (float) Math.sin(pitch) * STEP_SIZE;
            z += (float) Math.sin(yaw) * cosPitch * STEP_SIZE;

            int wxi = Math.round(x);
            int wyi = Math.round(y);
            int wzi = Math.round(z);
            if (wyi < Y_FLOOR || wyi >= WORLD_HEIGHT) break;
            int surface = heightMapGenerator.generateHeight(wxi, wzi);
            // Was `surface <= SEA_LEVEL + WATER_CLEARANCE`, i.e. "stop anywhere within 5
            // blocks of global sea level". With per-column water that test is both too broad
            // and too narrow: it killed every worm under dry coastal flats (surface 320-325,
            // a large share of the lowlands) while saying nothing about a river or lake
            // sitting hundreds of blocks higher. Gate on whether THIS column actually holds
            // water instead. WaterGuard.seals still does the precise per-cell sealing below;
            // this is only the coarse early-out for the walk.
            int water = heightMapGenerator.waterLevel(wxi, wzi);
            if (water != TerrainTile.NO_WATER && surface <= water + WATER_CLEARANCE) break;
            if (wyi > surface + BREACH_OVERHEAD) break;

            // Zone for the NEXT step, reusing the surface/water this step already resolved.
            zone = CaveWaterTable.zoneAt(waterTable.tableFrom(wxi, wzi, surface, water), wyi);

            float radius = BASE_RADIUS + radiusNoise.noise3D(x * RADIUS_SCALE, y * RADIUS_SCALE, z * RADIUS_SCALE) * RADIUS_AMP;
            if (radius < MIN_RADIUS) radius = MIN_RADIUS;
            carveEllipsoid(wxi, wyi, wzi, radius, targetCx, targetCz,
                    targetHeights, waterGuard, mask);

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
    private void carveEllipsoid(int wx, int wy, int wz, float radius, int targetCx,
                                int targetCz, int[] targetHeights, int[] waterGuard, BitSet mask) {
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
                int idx = bx * CHUNK_SIZE + bz;
                int surface = targetHeights[idx];
                // Only skip columns at/below the world floor; the per-cell WaterGuard check
                // below is what actually keeps carving away from water, and it understands
                // rivers and lakes at any altitude. The old `surface <= SEA_LEVEL + 1` test
                // additionally blanked every column near y=320 regardless of whether any
                // water was there.
                if (surface <= 1) continue;
                float horizTerm = (ox * ox + oz * oz) * invRxz2;
                if (horizTerm >= 1f) continue;
                float maxOyTerm = 1f - horizTerm;
                for (int oy = -ry; oy <= ry; oy++) {
                    if ((oy * oy) * invRy2 >= maxOyTerm) continue;
                    int by = wy + oy;
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    if (WaterGuard.seals(waterGuard, idx, by, WATER_CLEARANCE)) continue;
                    mask.set(LocalBlockKey.pack(bx, by, bz));
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
