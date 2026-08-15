package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.BitSet;
import java.util.Random;

/**
 * Ravines — long, narrow, deep chasms that cut down from the surface.
 *
 * <p>Every other carver here is subterranean and has to get lucky to become findable. A
 * ravine is the opposite: it is anchored to the surface and cuts <em>downward</em>, so it
 * breaks into open air by construction. That makes it two things at once — a dramatic
 * landmark, and the most reliable cave entrance in the generator.
 *
 * <p>Shape: a cross-section swept along a gently-curving horizontal path, squashed hard in
 * X/Z and stretched in Y — the inverse of {@link CavernCarver}'s squash. Width tapers toward
 * both ends of the path and the floor rises there, so a ravine closes to a point rather than
 * ending in a sheer wall. A vertical noise channel modulates the floor along the length so
 * it steps and undulates instead of reading as a machined trench.
 *
 * <h2>Why one ravine does not look like the next</h2>
 *
 * <p>The first version drew only four numbers per ravine — position, heading, length, depth,
 * width — and ran every one of them through the same fixed profile: a sine taper applied
 * identically to width and depth, a symmetric split about the midpoint, a constant drift
 * rate, and a straight-walled cross-section. Different sizes, one shape. Once you had seen
 * two you had seen all of them.
 *
 * <p>So the profile itself is now drawn per ravine, and the parameters are deliberately
 * chosen to be ones that change the <em>silhouette</em> rather than the scale:
 *
 * <ul>
 *   <li>{@code widthPow} / {@code depthPow} — separate taper exponents for the width and
 *       depth profiles. Below 1 the ravine holds full size almost to its tips and ends
 *       bluntly; above 1 it draws out into a long spindle. Decoupling the two is what
 *       produces a deep narrow slot at one extreme and a broad shallow gorge at the other,
 *       from the same pair of size draws.
 *   <li>{@code flare} — how much the cross-section widens from floor to rim. Zero is a
 *       sheer-walled slot canyon; high is an open V-shaped gorge you can see down into.
 *   <li>{@code sinuosity} — a multiplier on the per-step yaw drift. Some ravines run
 *       nearly dead straight, others meander across their whole length.
 *   <li>{@code wobble} — an independent noise channel on the width, with its own per-ravine
 *       amplitude and wavelength, so walls bulge into chambers and pinch to slots along the
 *       length instead of following the taper monotonically.
 *   <li>{@code tilt} — tips the floor along the length, so one arm bottoms out deeper than
 *       the other rather than the deepest point always sitting at the midpoint.
 *   <li>{@code armSplit} — the two arms get different shares of the length, breaking the
 *       mirror symmetry about the anchor.
 * </ul>
 *
 * <p>The shared path-noise channel is also sampled through a per-ravine phase offset. Without
 * it, two ravines crossing the same neighbourhood read the same meander out of the same field
 * and curve in sympathy — the shape variety above would then be undone by every ravine in a
 * region bending the same way.
 *
 * <p>Follows the same contract as the other carvers: deterministic from
 * {@code (seed, chunk)}, discovered by scanning source chunks within {@link #SCAN_RADIUS},
 * and gated per carved column through {@link WaterGuard}. A ravine that clips a riverbed
 * would drain it permanently, and a ravine is much wider than a worm, so its clearance is
 * correspondingly larger.
 */
public final class RavineCarver {

    /** 1 in N chunks spawns a ravine. Landmarks, not texture. */
    private static final int RAVINE_CHUNK_DIVISOR = 85;

    /** Depth below the local surface that the ravine floor reaches. */
    private static final int DEPTH_MIN = 45;
    private static final int DEPTH_MAX = 110;

    /** Half-width at the ravine's midpoint, in blocks. Tapers to nothing at the ends. */
    private static final float HALF_WIDTH_MIN = 2.5f;
    private static final float HALF_WIDTH_MAX = 6.0f;

    /** Path length in blocks, and the step along it. */
    private static final int LENGTH_MIN = 70;
    private static final int LENGTH_MAX = 170;
    private static final float STEP_SIZE = 1.0f;

    /** Per-step yaw drift, radians per unit noise, before {@code sinuosity} scaling. */
    private static final float YAW_DRIFT = 0.06f;
    private static final float YAW_SCALE = 1f / 64f;
    /**
     * Range of the per-ravine drift multiplier: near-straight cuts through to meanders.
     *
     * <p>The ceiling is a real constraint, not a taste setting. The drift is driven by
     * coherent noise sampled along the path, so it does not cancel out — a sustained lobe
     * turns the walk through a consistent arc. Much above this the arc closes on itself
     * inside one arm's length and the swept discs merge into an open bowl tens of blocks
     * across, which is a quarry, not a ravine.
     */
    private static final float SINUOSITY_MIN = 0.35f;
    private static final float SINUOSITY_MAX = 1.5f;

    /** Floor-undulation channel: how far the floor wanders along the length, in blocks. */
    private static final float FLOOR_AMP = 7f;
    private static final float FLOOR_SCALE = 1f / 40f;

    /**
     * Taper exponent range for the width and depth profiles. 1.0 reproduces the original
     * plain sine taper; the spread either side of it is where the silhouette variety
     * comes from.
     */
    private static final float TAPER_POW_MIN = 0.5f;
    private static final float TAPER_POW_MAX = 2.3f;

    /** Maximum rim flare: the widest cross-section is {@code (1 + FLARE_MAX)} x its floor. */
    private static final float FLARE_MAX = 0.75f;

    /** Width-wobble channel: peak fractional swing, and the wavelength range it runs at. */
    private static final float WOBBLE_AMP_MAX = 0.45f;
    private static final float WOBBLE_SCALE_MIN = 1f / 55f;
    private static final float WOBBLE_SCALE_MAX = 1f / 16f;

    /** Peak floor tilt along the length, as a fraction of the ravine's depth. */
    private static final float TILT_MAX = 0.30f;

    /** Fraction of the total length given to the first arm; the other arm gets the rest. */
    private static final float ARM_SPLIT_MIN = 0.30f;
    private static final float ARM_SPLIT_MAX = 0.70f;

    /** Below this the cut is a crack rather than a passage, and the arm ends. */
    private static final float MIN_USEFUL_HALF_WIDTH = 0.8f;

    /**
     * How high above its floor the ravine cuts. Generous — it must reach the surface from
     * the deepest floor, and the per-column surface clamp trims the excess.
     */
    private static final int OVERCUT = 160;

    /** Widest half-width any ravine can reach: full width, peak wobble, full rim flare. */
    private static final float MAX_HALF_WIDTH =
            HALF_WIDTH_MAX * (1f + WOBBLE_AMP_MAX) * (1f + FLARE_MAX);

    /**
     * Worst-case reach from the anchor. The walk starts at the ravine's anchor and runs
     * outward in two arms, so the reach is the longer arm — {@link #ARM_SPLIT_MAX} of the
     * length, not the whole of it — plus the widest cut and slack.
     */
    private static final float MAX_REACH = (LENGTH_MAX * ARM_SPLIT_MAX) + MAX_HALF_WIDTH + 2f;
    /** Source-chunk scan radius (chunks), large enough to cover any ravine reaching in. */
    public static final int SCAN_RADIUS =
            (int) Math.ceil(MAX_REACH / WorldConfiguration.CHUNK_SIZE) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    /** Clearance from any wet bed, derived from the widest possible cut. */
    private static final int WATER_CLEARANCE = (int) Math.ceil(MAX_HALF_WIDTH) + 2;

    private final long seed;
    private final HeightMapGenerator heightMapGenerator;
    private final NoiseGenerator pathNoise;

    public RavineCarver(long seed, HeightMapGenerator heightMapGenerator) {
        this.seed = seed;
        this.heightMapGenerator = heightMapGenerator;
        this.pathNoise = new NoiseGenerator(seed + 577, 1, 0.5, 2.0);
    }

    /**
     * <p>Note the splitmix64 finalizer. The sibling carvers get away with a single rotate
     * because their divisors are powers of two, so {@code floorMod} reads only well-mixed
     * low bits. {@link #RAVINE_CHUNK_DIVISOR} is not a power of two, and without the
     * finalizer the residual structure in those low bits made this return false for every
     * chunk in a 25x25 sample — a carver that silently never spawned.
     */
    public boolean hasRavine(int cx, int cz) {
        long h = seed ^ 0x5AF17E9A5AF17E9AL;
        h ^= (long) cx * 0xD6E8FEB86659FD93L;
        h = Long.rotateLeft(h, 27);
        h ^= (long) cz * 0xA24BAED4963EE407L;
        h ^= h >>> 30; h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27; h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return Math.floorMod(h, RAVINE_CHUNK_DIVISOR) == 0;
    }

    private long ravineRngSeed(int cx, int cz) {
        return ((seed * 0x27BB2EE687B0B0FDL) ^ ((long) cx * 0x9E3779B97F4A7C15L))
                ^ ((long) cz * 0xC2B2AE3D27D4EB4FL);
    }

    /** Carve mask for the target chunk, packed by {@link LocalBlockKey#pack(int,int,int)}. */
    public BitSet carveMaskForChunk(int chunkX, int chunkZ, int[] targetHeights, int[] waterLevels) {
        int[] waterGuard =
                WaterGuard.guardPlane(targetHeights, waterLevels, heightMapGenerator, chunkX, chunkZ);
        BitSet mask = new BitSet();
        for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
            for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!hasRavine(srcCx, srcCz)) continue;
                carveRavine(srcCx, srcCz, chunkX, chunkZ, targetHeights, waterGuard, mask);
            }
        }
        return mask;
    }

    private void carveRavine(int srcCx, int srcCz, int targetCx, int targetCz,
                             int[] targetHeights, int[] waterGuard, BitSet mask) {
        Random rng = new Random(ravineRngSeed(srcCx, srcCz));
        float x = srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float z = srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE);
        float yaw = rng.nextFloat() * (float) (Math.PI * 2);
        int length = LENGTH_MIN + rng.nextInt(LENGTH_MAX - LENGTH_MIN);
        int depth = DEPTH_MIN + rng.nextInt(DEPTH_MAX - DEPTH_MIN);
        float halfWidth = HALF_WIDTH_MIN + rng.nextFloat() * (HALF_WIDTH_MAX - HALF_WIDTH_MIN);

        // The shape draws. See the class doc for what each one does to the silhouette.
        float widthPow = lerp(TAPER_POW_MIN, TAPER_POW_MAX, rng.nextFloat());
        float depthPow = lerp(TAPER_POW_MIN, TAPER_POW_MAX, rng.nextFloat());
        float flare = rng.nextFloat() * FLARE_MAX;
        float sinuosity = lerp(SINUOSITY_MIN, SINUOSITY_MAX, rng.nextFloat());
        float wobbleAmp = rng.nextFloat() * WOBBLE_AMP_MAX;
        float wobbleScale = lerp(WOBBLE_SCALE_MIN, WOBBLE_SCALE_MAX, rng.nextFloat());
        float tilt = (rng.nextFloat() - 0.5f) * 2f * TILT_MAX;
        float armSplit = lerp(ARM_SPLIT_MIN, ARM_SPLIT_MAX, rng.nextFloat());
        // Phase offsets decorrelate this ravine's meander and wobble from its neighbours',
        // which otherwise read the same values out of the same shared noise field.
        float phaseX = rng.nextFloat() * 4096f;
        float phaseZ = rng.nextFloat() * 4096f;

        // Walk outward from the anchor in both directions, so the widest, deepest part sits
        // near the anchor and both ends taper. Walking from one end instead would put a
        // sheer full-depth wall at the origin. The arms get unequal shares of the length,
        // and opposite tilt signs, so the result is not a mirror image of itself.
        for (int dir = 0; dir < 2; dir++) {
            float px = x, pz = z;
            float pyaw = dir == 0 ? yaw : yaw + (float) Math.PI;
            int arm = Math.round(length * (dir == 0 ? armSplit : 1f - armSplit));
            if (arm < 2) continue;
            float tiltSign = dir == 0 ? 1f : -1f;

            for (int step = 0; step < arm; step++) {
                float n = pathNoise.noise3D((px + phaseX) * YAW_SCALE, 0f, (pz + phaseZ) * YAW_SCALE);
                pyaw += n * YAW_DRIFT * sinuosity;
                px += (float) Math.cos(pyaw) * STEP_SIZE;
                pz += (float) Math.sin(pyaw) * STEP_SIZE;

                // Taper: full size near the anchor, closing toward the tip. Width and depth
                // run different exponents, so the two profiles no longer track each other.
                float progress = step / (float) arm;
                float sine = (float) Math.sin((1f - progress) * Math.PI * 0.5);
                float w = halfWidth * (float) Math.pow(sine, widthPow);
                if (wobbleAmp > 0f) {
                    float wob = pathNoise.noise3D((px + phaseX) * wobbleScale, 256f,
                            (pz + phaseZ) * wobbleScale);
                    w *= 1f + wobbleAmp * wob;
                }
                if (w < MIN_USEFUL_HALF_WIDTH) break;

                float depthScale = (float) Math.pow(sine, depthPow) * (1f + tiltSign * tilt * progress);
                int cutDepth = Math.max(1, Math.round(depth * depthScale));

                carveSlice(Math.round(px), Math.round(pz), w, flare, cutDepth,
                        targetCx, targetCz, targetHeights, waterGuard, mask);
            }
        }
    }

    /**
     * Carves one vertical slice of the ravine at a point on its path: a disc in X/Z
     * extended from the floor up to (and through) the surface, its radius growing from
     * {@code halfWidth} at the floor to {@code halfWidth * (1 + flare)} at the rim.
     *
     * <p>The radius varying with height is why this iterates Y innermost per column rather
     * than testing the disc once: a slot canyon and a V-shaped gorge are the same sweep with
     * different {@code flare}, and that difference only exists per-Y.
     */
    private void carveSlice(int wx, int wz, float halfWidth, float flare, int cutDepth,
                            int targetCx, int targetCz, int[] targetHeights, int[] waterGuard,
                            BitSet mask) {
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        float rimWidth = halfWidth * (1f + flare);
        int r = (int) Math.ceil(rimWidth);
        // Bounding-box reject first — most of a ravine is nowhere near the target chunk.
        if (wx + r < targetBaseX || wx - r >= targetBaseX + CHUNK_SIZE) return;
        if (wz + r < targetBaseZ || wz - r >= targetBaseZ + CHUNK_SIZE) return;

        float rimWidthSq = rimWidth * rimWidth;
        float floorNoise = pathNoise.noise3D(wx * FLOOR_SCALE, 512f, wz * FLOOR_SCALE);

        for (int ox = -r; ox <= r; ox++) {
            int bx = wx + ox - targetBaseX;
            if (bx < 0 || bx >= CHUNK_SIZE) continue;
            for (int oz = -r; oz <= r; oz++) {
                int bz = wz + oz - targetBaseZ;
                if (bz < 0 || bz >= CHUNK_SIZE) continue;
                float distSq = ox * ox + oz * oz;
                // Outside even the rim: no height in this column can be inside the cut.
                if (distSq >= rimWidthSq) continue;

                int idx = bx * CHUNK_SIZE + bz;
                int surface = targetHeights[idx];
                if (surface <= 1) continue;

                int floor = surface - cutDepth + Math.round(floorNoise * FLOOR_AMP);
                if (floor < 2) floor = 2;
                int top = Math.min(surface, floor + OVERCUT);
                int span = Math.max(1, top - floor);
                for (int by = floor; by < top; by++) {
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    // Flare: the walls lean outward as they rise, so the cut is a V rather
                    // than a slot wherever this ravine drew a non-zero flare.
                    float radius = halfWidth * (1f + flare * ((by - floor) / (float) span));
                    if (distSq >= radius * radius) continue;
                    if (WaterGuard.seals(waterGuard, idx, by, WATER_CLEARANCE)) continue;
                    mask.set(LocalBlockKey.pack(bx, by, bz));
                }
            }
        }
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
