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
 *   <li>{@code dip} — the angle the whole cut drives into the ground at. See below.
 * </ul>
 *
 * <h2>Dip: ravines do not all cut straight down</h2>
 *
 * <p>Every other parameter above varies the ravine's silhouette while leaving it a vertical
 * extrusion — a shape stamped into the ground and pulled straight up. {@code dip} is the
 * angle of the cut measured from horizontal: 90&deg; is that classic straight-down chasm,
 * and anything less drives the chasm diagonally into the world, so it bottoms out under one
 * place and breaks the surface some distance to the side of it.
 *
 * <p>The cross-section is <em>rotated</em> about the path axis, not sheared along it. That
 * distinction is the whole feature: a shear would keep the horizontal width fixed and so
 * collapse the gap measured perpendicular to the walls as the ravine leans over, turning a
 * 23&deg; ravine into a hairline crack. Rotating widens the horizontal footprint by
 * {@code 1 / sin(dip)} instead, and a shallow ravine stays a chasm you can walk down — it
 * just descends sideways as well as down. See {@link #carveSlice} for the test that falls
 * out of this.
 *
 * <p>The draw is biased hard toward vertical ({@link #DIP_BIAS_POW}), because the diagonal
 * cut is a landmark within a class of landmarks: most ravines should still read as the
 * chasm the player already knows, so that the slanted one reads as unusual when it appears.
 * It also drifts along the length off its own noise channel, so a ravine can start near
 * vertical and lean over as it runs rather than being one rigid plane end to end.
 *
 * <p>Note that {@code dip} and {@code tilt} are unrelated despite the near-synonym.
 * {@code tilt} tips the <em>floor profile along the length</em>; the cut itself stays
 * vertical. {@code dip} leans the cut itself, perpendicular to the path.
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
    private static final int RAVINE_CHUNK_DIVISOR = 450;

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

    /**
     * Dip range, in degrees from horizontal. {@link #DIP_MAX_DEG} is the vertical cut every
     * ravine used to be; {@link #DIP_MIN_DEG} is a chasm driven in at a hard slant.
     *
     * <p>The floor is not a taste setting either. Lateral run is {@code depth / tan(dip)},
     * so it grows without bound as the dip approaches horizontal — and that run is what
     * {@link #SCAN_RADIUS} has to cover, at quadratic cost in the source scan. 23&deg;
     * already carries the deepest ravines over 350 blocks sideways.
     */
    private static final float DIP_MIN_DEG = 23f;
    private static final float DIP_MAX_DEG = 90f;

    /**
     * Exponent on the dip draw: {@code dip = MAX - (MAX - MIN) * u^POW}. At 3, about half of
     * all ravines land steeper than 80&deg; and only a few percent below 35&deg;.
     */
    private static final float DIP_BIAS_POW = 3f;

    /** Per-ravine dip-drift amplitude ceiling, and the wavelength the drift runs at. */
    private static final float DIP_DRIFT_MAX_DEG = 25f;
    private static final float DIP_DRIFT_SCALE = 1f / 90f;

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

    /** Trig at the shallowest dip, which is where every worst-case bound below is set. */
    private static final float MIN_SIN_DIP = (float) Math.sin(Math.toRadians(DIP_MIN_DEG));
    private static final float MAX_SHEAR = (float) (1.0 / Math.tan(Math.toRadians(DIP_MIN_DEG)));

    /**
     * Widest <em>horizontal</em> half-width. Rotating the cross-section holds the gap
     * perpendicular to the walls constant, so the footprint on the ground stretches by
     * {@code 1 / sin(dip)} as the ravine leans.
     */
    private static final float MAX_HALF_WIDTH_H = MAX_HALF_WIDTH / MIN_SIN_DIP;

    /**
     * How far sideways the deepest, shallowest-dipping ravine travels between its floor and
     * the surface. Note the {@link #TILT_MAX} factor: {@code cutDepth} is
     * {@code depth * depthScale}, and {@code depthScale} carries the tilt term, so a slice
     * can cut deeper than {@link #DEPTH_MAX}. Missing that term is how this bound goes
     * stale by 25% and starts clipping ravines at chunk seams.
     */
    private static final float MAX_LATERAL_RUN =
            (DEPTH_MAX * (1f + TILT_MAX) + FLOOR_AMP) * MAX_SHEAR;

    /**
     * Worst-case reach from the anchor. The walk starts at the ravine's anchor and runs
     * outward in two arms, so the reach is the longer arm — {@link #ARM_SPLIT_MAX} of the
     * length, not the whole of it — plus the sideways run of the lean, the widest cut and
     * slack.
     */
    private static final float MAX_REACH = (LENGTH_MAX * ARM_SPLIT_MAX)
            + MAX_LATERAL_RUN + MAX_HALF_WIDTH_H + 2f;
    /** Source-chunk scan radius (chunks), large enough to cover any ravine reaching in. */
    public static final int SCAN_RADIUS =
            (int) Math.ceil(MAX_REACH / WorldConfiguration.CHUNK_SIZE) + 1;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

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

    /**
     * The contribution of <em>one</em> named ravine to one target chunk — the primitive
     * {@link #carveMaskForChunk} loops over its scan window.
     *
     * <p>Exposed because a ravine is only meaningful as a whole, and a whole ravine spans
     * far more than the chunk it is anchored in. Anything wanting to reason about one
     * ravine's shape has to reassemble it across chunks, and the alternative — carving a
     * neighbourhood and hoping no second ravine reaches into it — gets less workable the
     * rarer ravines are, because isolation over a {@link #SCAN_RADIUS}-chunk window becomes
     * the vanishingly unlikely case.
     *
     * @return the mask, empty if {@code (srcCx, srcCz)} holds no ravine or it does not reach
     */
    public BitSet carveMaskForChunkFrom(int srcCx, int srcCz, int targetCx, int targetCz,
                                        int[] targetHeights, int[] waterLevels) {
        BitSet mask = new BitSet();
        if (!hasRavine(srcCx, srcCz)) return mask;
        int[] waterGuard = WaterGuard.guardPlane(
                targetHeights, waterLevels, heightMapGenerator, targetCx, targetCz);
        carveRavine(srcCx, srcCz, targetCx, targetCz, targetHeights, waterGuard, mask);
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
        // Appended after the draws above rather than slotted in beside the other shape
        // parameters, deliberately: the draw order is the determinism contract, so taking
        // these last leaves every ravine's existing size and silhouette exactly as it was
        // and adds the lean on top, instead of reshuffling the whole field.
        float dipBase = DIP_MAX_DEG - (DIP_MAX_DEG - DIP_MIN_DEG)
                * (float) Math.pow(rng.nextFloat(), DIP_BIAS_POW);
        float dipDriftAmp = rng.nextFloat() * DIP_DRIFT_MAX_DEG;

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

                // Dip drifts along the length off its own noise channel, so the cut can
                // stand up and lean over as it runs. The wavelength is long relative to a
                // step, which is what keeps consecutive slices near-parallel and the swept
                // solid connected — a fast-twisting axis would chop it into offset shards.
                float dipNoise = pathNoise.noise3D((px + phaseX) * DIP_DRIFT_SCALE, 768f,
                        (pz + phaseZ) * DIP_DRIFT_SCALE);
                float dipRad = (float) Math.toRadians(
                        clamp(dipBase + dipDriftAmp * dipNoise, DIP_MIN_DEG, DIP_MAX_DEG));
                float sinDip = (float) Math.sin(dipRad);
                float shear = (float) Math.cos(dipRad) / sinDip;

                // Which way it leans: perpendicular to the local heading, and negated for
                // the second arm. Without that flip the arm's reversed heading flips the
                // normal with it, and the two arms lean to opposite sides of the anchor —
                // a ravine folded into a Z rather than one cut driven in at an angle.
                float leanSign = dir == 0 ? 1f : -1f;
                float nx = -(float) Math.sin(pyaw) * leanSign;
                float nz = (float) Math.cos(pyaw) * leanSign;

                carveSlice(Math.round(px), Math.round(pz), w, flare, cutDepth,
                        sinDip, shear, nx, nz,
                        targetCx, targetCz, targetHeights, waterGuard, mask);
            }
        }
    }

    /**
     * Carves one slice of the ravine at a point on its path: a disc swept along an axis that
     * rises from the floor at {@code dip} degrees from horizontal, leaning in {@code (nx, nz)}.
     * The radius grows from {@code halfWidth} at the floor to {@code halfWidth * (1 + flare)}
     * at the rim.
     *
     * <h2>The membership test</h2>
     *
     * <p>Split a column's offset from the path point into a component along the lean,
     * {@code u}, and one along the path, {@code v}. Only {@code u} moves with height: at
     * {@code t} blocks above the floor the axis has travelled {@code shear * t} sideways,
     * where {@code shear = cot(dip)}. So a block is inside the cut when
     *
     * <pre>  v² + ((u - shear*t) * sin(dip))² &lt; radius²</pre>
     *
     * <p>That {@code sin(dip)} is the difference between rotating the cross-section and
     * merely shearing it, and it is the whole reason a shallow ravine is still a ravine.
     * Drop it and the horizontal width stays fixed while the walls lean over, so the gap
     * measured perpendicular to them closes as {@code sin(dip)} — at 23&deg; a 12-block-wide
     * chasm becomes a 4-block crack. Keeping it stretches the footprint on the ground by
     * {@code 1 / sin(dip)} instead, holding the walkable width constant whatever the angle.
     *
     * <p>{@code floor} stays per-column, as it was when the cut was vertical, and the
     * diagonal falls out of that rather than needing to be imposed: a column {@code u} to
     * the side carves at {@code surface - cutDepth + u * tan(dip)}, so the cut bottoms out
     * beneath the path, climbs sideways, and breaks the surface in a stripe offset by the
     * full lateral run. In between it is roofed over — which is what a slanted slot cut
     * through level ground actually looks like.
     */
    private void carveSlice(int wx, int wz, float halfWidth, float flare, int cutDepth,
                            float sinDip, float shear, float nx, float nz,
                            int targetCx, int targetCz, int[] targetHeights, int[] waterGuard,
                            BitSet mask) {
        int targetBaseX = targetCx * CHUNK_SIZE;
        int targetBaseZ = targetCz * CHUNK_SIZE;
        float rimWidth = halfWidth * (1f + flare);

        // Footprint bounds. A leaning slice is not a disc around the path point but a band
        // running from there out to where its axis breaks the surface, so the box is taken
        // over both ends of the axis. Intersecting it with the chunk up front — rather than
        // sweeping the full extent and discarding — matters here in a way it did not when
        // the cut was vertical: the run reaches a few hundred blocks at a shallow dip, and
        // scanning that square per slice would be six figures of rejected columns each time.
        float run = shear * (cutDepth + FLOOR_AMP);
        float pad = rimWidth / sinDip + 1f;
        int x0 = Math.max(targetBaseX,
                (int) Math.floor(wx + Math.min(0f, run * nx) - pad));
        int x1 = Math.min(targetBaseX + CHUNK_SIZE - 1,
                (int) Math.ceil(wx + Math.max(0f, run * nx) + pad));
        if (x0 > x1) return;
        int z0 = Math.max(targetBaseZ,
                (int) Math.floor(wz + Math.min(0f, run * nz) - pad));
        int z1 = Math.min(targetBaseZ + CHUNK_SIZE - 1,
                (int) Math.ceil(wz + Math.max(0f, run * nz) + pad));
        if (z0 > z1) return;

        float rimWidthSq = rimWidth * rimWidth;
        float floorNoise = pathNoise.noise3D(wx * FLOOR_SCALE, 512f, wz * FLOOR_SCALE);
        // Clearance from wet beds, from this slice's own horizontal reach rather than the
        // carver's global worst case. A vertical ravine is gated exactly as it always was;
        // a leaning one, which really does span further, is gated on what it really spans.
        int waterClearance = (int) Math.ceil(rimWidth / sinDip) + 2;

        for (int wcx = x0; wcx <= x1; wcx++) {
            int bx = wcx - targetBaseX;
            float ox = wcx - wx;
            for (int wcz = z0; wcz <= z1; wcz++) {
                int bz = wcz - targetBaseZ;
                float oz = wcz - wz;

                // Along the path: a fixed cross-section distance, independent of height.
                // Outside the rim here and no height in this column can be inside the cut.
                float v = oz * nx - ox * nz;
                float vSq = v * v;
                if (vSq >= rimWidthSq) continue;
                float u = ox * nx + oz * nz;

                int idx = bx * CHUNK_SIZE + bz;
                int surface = targetHeights[idx];
                if (surface <= 1) continue;

                int floor = surface - cutDepth + Math.round(floorNoise * FLOOR_AMP);
                if (floor < 2) floor = 2;
                int top = Math.min(surface, floor + OVERCUT);
                if (top <= floor) continue;

                // Solve the membership test for t rather than walking the column and testing
                // every block. A leaning cut passes through any one column in a band a few
                // blocks tall, and finding it directly is what pays for the wider footprint.
                int yLo = floor;
                int yHi = top;
                if (shear > 1e-4f) {
                    float q = (float) Math.sqrt(rimWidthSq - vSq) / sinDip;
                    yLo = Math.max(floor, floor + (int) Math.floor((u - q) / shear));
                    yHi = Math.min(top, floor + (int) Math.ceil((u + q) / shear) + 1);
                }

                for (int by = yLo; by < yHi; by++) {
                    if (by < 1 || by >= WORLD_HEIGHT) continue;
                    float t = by - floor;
                    // Flare: the walls lean outward as they rise, so the cut is a V rather
                    // than a slot wherever this ravine drew a non-zero flare. Measured up
                    // the ravine's own axis, not up the column — on a leaning cut the
                    // column holds only a sliver of the axis, and running the full flare
                    // across that sliver would splay every slice open at its own thickness.
                    float radius = halfWidth * (1f + flare * Math.min(1f, t / cutDepth));
                    float lateral = (u - shear * t) * sinDip;
                    if (vSq + lateral * lateral >= radius * radius) continue;
                    if (WaterGuard.seals(waterGuard, idx, by, waterClearance)) continue;
                    mask.set(LocalBlockKey.pack(bx, by, bz));
                }
            }
        }
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
