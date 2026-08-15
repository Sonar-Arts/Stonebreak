package com.stonebreak.world.generation;

import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.generation.diffusion.DryHillsTileSource;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.RavineCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ravines must not all be the same shape.
 *
 * <p>Ravines are the generator's most conspicuous landmark — surface-anchored, tens of blocks
 * deep, visible from a distance — so they are the feature a player sees most often and
 * compares most directly against the last one. The original carver drew only size parameters
 * and pushed every ravine through one fixed profile: a sine taper shared by width and depth, a
 * symmetric split about the anchor, one drift rate, straight walls. The results differed in
 * scale and in nothing else.
 *
 * <p>The failure mode this guards is a quiet one. Any of the shape draws in
 * {@link RavineCarver} could be dropped, clamped to a constant, or have its range collapsed
 * during a retune, and nothing else in the suite would notice: the ravines would still carve,
 * still reach the surface, still count toward cave volume. Only their variety would be gone,
 * and variety is not something a volume metric can see.
 *
 * <p>Method: carve ravines one at a time through
 * {@link RavineCarver#carveMaskForChunkFrom}, and reduce each to four shape descriptors that
 * are independent of how big it is. The assertion is on the <em>spread</em> of each descriptor
 * across the sample — a coefficient of variation — because that is the property being
 * defended. A generator making one shape at many sizes scores near zero on all four.
 *
 * <p>Carving each ravine by name, rather than picking ones that happen to have no neighbour
 * within reach and carving the neighbourhood wholesale, is not a convenience. Ravines are rare
 * and reach {@link RavineCarver#SCAN_RADIUS} chunks, so "no other ravine in range" is a
 * coincidence that a sweep of any practical size no longer turns up even once.
 */
public class RavineShapeVarietyTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /** How far the search sweeps, and how many ravines it needs to find. */
    private static final int SEARCH_CHUNKS = 400;
    private static final int MIN_SAMPLES = 10;

    /** Radius carved around a sample's anchor: everything the ravine can possibly reach. */
    private static final int MEASURE_CHUNKS = RavineCarver.SCAN_RADIUS;

    /**
     * Minimum coefficient of variation (stddev / mean) per descriptor.
     *
     * <p>Set well under what the current draws produce — this is a floor against the
     * mechanism being switched off, not a target to tune against. Measured at the time of
     * writing: depth 0.49, elongation 0.43, wall profile 0.16, lean 1.17.
     */
    private static final double MIN_DEPTH_CV = 0.10;
    private static final double MIN_ELONGATION_CV = 0.20;
    private static final double MIN_WALL_PROFILE_CV = 0.10;
    private static final double MIN_LEAN_CV = 0.30;

    /**
     * At least one sample must lean this far — {@code cot(dip)}, so 0.30 is a dip of 73&deg;.
     *
     * <p>The CV above catches the dip draw being collapsed to a constant, but not the case
     * that matters most: every sample coming out near-vertical with only noise between them,
     * which is what a retune that quietly raised the floor of the dip range would produce.
     * The spread would survive; the feature would not.
     *
     * <p>Measured 0.71 (a 54&deg; dip) at the time of writing. Note that the draw is biased
     * hard toward vertical, so the shallowest few degrees of the range are not expected to
     * show up in a sample this size — this is a floor on "some ravine visibly slants", not on
     * reaching {@code DIP_MIN_DEG}.
     */
    private static final double MIN_PEAK_LEAN = 0.30;

    @Test
    public void ravinesDifferInShapeAndNotOnlyInSize() {
        DryHillsTileSource src = new DryHillsTileSource();
        RavineCarver carver = new RavineCarver(SEED, new HeightMapGenerator(src));

        List<Shape> shapes = new ArrayList<>();
        for (int cx = 0; cx < SEARCH_CHUNKS && shapes.size() < MIN_SAMPLES; cx++) {
            for (int cz = 0; cz < SEARCH_CHUNKS && shapes.size() < MIN_SAMPLES; cz++) {
                if (!carver.hasRavine(cx, cz)) {
                    continue;
                }
                Shape shape = measure(carver, cx, cz);
                if (shape != null) {
                    shapes.add(shape);
                }
            }
        }

        assertTrue(shapes.size() >= MIN_SAMPLES, "found only " + shapes.size()
                + " ravines to measure; needed " + MIN_SAMPLES);

        System.out.println("[ravines] shape descriptors per sample:");
        for (Shape s : shapes) {
            System.out.printf("  depth %.1f  elongation %.2f  wall profile %.2f  lean %.2f "
                            + "(dip %.0f deg)  (%d voxels)%n",
                    s.depth, s.elongation, s.wallProfile, s.lean,
                    Math.toDegrees(Math.atan2(1.0, s.lean)), s.voxels);
        }

        double depthCv = cv(shapes.stream().mapToDouble(s -> s.depth).toArray());
        double elongationCv = cv(shapes.stream().mapToDouble(s -> s.elongation).toArray());
        double wallProfileCv = cv(shapes.stream().mapToDouble(s -> s.wallProfile).toArray());
        double leanCv = cv(shapes.stream().mapToDouble(s -> s.lean).toArray());
        double peakLean = shapes.stream().mapToDouble(s -> s.lean).max().orElse(0);
        System.out.printf("[ravines] spread over %d samples: depth %.2f, elongation %.2f, "
                        + "wall profile %.2f, lean %.2f (peak lean %.2f)%n",
                shapes.size(), depthCv, elongationCv, wallProfileCv, leanCv, peakLean);

        assertTrue(leanCv >= MIN_LEAN_CV, String.format(
                "ravine lean varies by only %.2f (need %.2f) — they are all driving into the "
                        + "ground at the same angle", leanCv, MIN_LEAN_CV));
        assertTrue(peakLean >= MIN_PEAK_LEAN, String.format(
                "the most slanted ravine in the sample leans only %.2f (need %.2f) — every one "
                        + "of them is cutting essentially straight down", peakLean, MIN_PEAK_LEAN));

        assertTrue(depthCv >= MIN_DEPTH_CV, String.format(
                "ravine depth profiles vary by only %.2f (need %.2f) — every ravine is cutting "
                        + "to the same relative depth", depthCv, MIN_DEPTH_CV));
        assertTrue(elongationCv >= MIN_ELONGATION_CV, String.format(
                "ravine footprints vary by only %.2f (need %.2f) — they are all tracing the "
                        + "same path shape", elongationCv, MIN_ELONGATION_CV));
        assertTrue(wallProfileCv >= MIN_WALL_PROFILE_CV, String.format(
                "ravine wall profiles vary by only %.2f (need %.2f) — they are all the same "
                        + "cross-section and taper, at different sizes", wallProfileCv,
                MIN_WALL_PROFILE_CV));
    }

    /** Carves one ravine alone and reduces it to scale-free shape descriptors. */
    private static Shape measure(RavineCarver carver, int scx, int scz) {
        int r = MEASURE_CHUNKS;
        int size = (2 * r + 1) * CHUNK;
        int[] minY = new int[size * size];
        int[] maxY = new int[size * size];
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxY, -1);
        long voxels = 0;

        // Per-level voxel moments, for the lean descriptor. Slicing the solid by height and
        // keeping only sums is what lets a whole ravine be measured without ever holding one.
        int[] levelCount = new int[WorldConfiguration.WORLD_HEIGHT];
        double[] levelX = new double[WorldConfiguration.WORLD_HEIGHT];
        double[] levelZ = new double[WorldConfiguration.WORLD_HEIGHT];
        double sumX = 0, sumZ = 0, sumXX = 0, sumXZ = 0, sumZZ = 0;

        int[] heights = new int[CHUNK * CHUNK];
        int[] water = new int[CHUNK * CHUNK];
        Arrays.fill(water, TerrainTile.NO_WATER);

        for (int dcx = -r; dcx <= r; dcx++) {
            for (int dcz = -r; dcz <= r; dcz++) {
                int cx = scx + dcx;
                int cz = scz + dcz;
                for (int lx = 0; lx < CHUNK; lx++) {
                    for (int lz = 0; lz < CHUNK; lz++) {
                        heights[lx * CHUNK + lz] =
                                DryHillsTileSource.height(cx * CHUNK + lx, cz * CHUNK + lz);
                    }
                }
                BitSet mask = carver.carveMaskForChunkFrom(scx, scz, cx, cz, heights, water);
                for (int b = mask.nextSetBit(0); b >= 0; b = mask.nextSetBit(b + 1)) {
                    int gx = (dcx + r) * CHUNK + LocalBlockKey.x(b);
                    int gz = (dcz + r) * CHUNK + LocalBlockKey.z(b);
                    int i = gx * size + gz;
                    int y = LocalBlockKey.y(b);
                    minY[i] = Math.min(minY[i], y);
                    maxY[i] = Math.max(maxY[i], y);
                    levelCount[y]++;
                    levelX[y] += gx;
                    levelZ[y] += gz;
                    sumX += gx; sumZ += gz;
                    sumXX += (double) gx * gx;
                    sumXZ += (double) gx * gz;
                    sumZZ += (double) gz * gz;
                    voxels++;
                }
            }
        }
        if (voxels == 0) {
            return null;
        }

        int x0 = size, x1 = -1, z0 = size, z1 = -1;
        long depthSum = 0;
        long columns = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int i = x * size + z;
                if (maxY[i] < 0) {
                    continue;
                }
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                z0 = Math.min(z0, z); z1 = Math.max(z1, z);
                depthSum += maxY[i] - minY[i] + 1;
                columns++;
            }
        }

        // Wall profile: how evenly the cut's depth is spread across its columns.
        //
        // A sheer-walled slot carves every column inside its width to the same floor, so its
        // column depths cluster and this lands low. A flared V only reaches a given column
        // once its widening wall passes that column's distance from the axis, so depth falls
        // off steadily from the axis outward and this rises. The length taper feeds in the
        // same way — a blunt-ended ravine holds full depth to its tips, a spindly one does
        // not. Both are exactly the profile draws under test, which is why this is measured
        // as a within-ravine spread rather than as another average.
        double meanDepth = depthSum / (double) columns;
        double depthVariance = 0;
        for (int i = 0; i < minY.length; i++) {
            if (maxY[i] < 0) {
                continue;
            }
            double d = maxY[i] - minY[i] + 1 - meanDepth;
            depthVariance += d * d;
        }
        depthVariance /= columns;

        int spanX = x1 - x0 + 1;
        int spanZ = z1 - z0 + 1;
        Shape shape = new Shape();
        shape.depth = meanDepth;
        shape.elongation = Math.max(spanX, spanZ) / (double) Math.min(spanX, spanZ);
        shape.wallProfile = Math.sqrt(depthVariance) / meanDepth;
        shape.lean = lean(levelCount, levelX, levelZ, voxels,
                sumX, sumZ, sumXX, sumXZ, sumZZ);
        shape.voxels = voxels;
        return shape;
    }

    /**
     * How far the cut travels sideways per block it rises — {@code cot(dip)}, 0 for the
     * straight-down chasm the carver used to make exclusively.
     *
     * <p>Measured as the horizontal offset between the centroid of the lowest quarter of the
     * ravine's voxels and that of the highest quarter, over their vertical separation.
     *
     * <p>The projection onto the footprint's <em>minor</em> axis is the part that has to be
     * right. A ravine is long, and two mechanisms already push its deep end along its length:
     * the arm split and the floor {@code tilt}. Both act along the major axis, so an
     * unprojected offset would read them as lean and this would score high on a perfectly
     * vertical ravine. The lean is perpendicular to the path by construction, which is the
     * minor axis — so projecting there keeps the two apart.
     */
    private static double lean(int[] levelCount, double[] levelX, double[] levelZ, long voxels,
                               double sumX, double sumZ,
                               double sumXX, double sumXZ, double sumZZ) {
        double n = voxels;
        double cxx = sumXX / n - (sumX / n) * (sumX / n);
        double czz = sumZZ / n - (sumZ / n) * (sumZ / n);
        double cxz = sumXZ / n - (sumX / n) * (sumZ / n);
        // Major axis of the footprint; the minor is its perpendicular.
        double major = 0.5 * Math.atan2(2 * cxz, cxx - czz);
        double mx = -Math.sin(major);
        double mz = Math.cos(major);

        long quarter = Math.max(1, voxels / 4);
        double[] low = quartileCentroid(levelCount, levelX, levelZ, quarter, true);
        double[] high = quartileCentroid(levelCount, levelX, levelZ, quarter, false);
        double dy = high[2] - low[2];
        if (dy < 1e-6) {
            return 0;
        }
        return Math.abs((high[0] - low[0]) * mx + (high[1] - low[1]) * mz) / dy;
    }

    /** Centroid {x, z, y} of the lowest or highest {@code want} voxels, by level. */
    private static double[] quartileCentroid(int[] levelCount, double[] levelX, double[] levelZ,
                                             long want, boolean fromBottom) {
        double ax = 0, az = 0, ay = 0;
        long taken = 0;
        for (int i = 0; i < levelCount.length && taken < want; i++) {
            int y = fromBottom ? i : levelCount.length - 1 - i;
            int available = levelCount[y];
            if (available == 0) {
                continue;
            }
            // Levels are taken whole until the last one, which is prorated so the split lands
            // exactly on the quartile rather than on whichever level happens to straddle it.
            double share = Math.min(available, want - taken) / (double) available;
            ax += levelX[y] * share;
            az += levelZ[y] * share;
            ay += (double) y * available * share;
            taken += Math.min(available, want - taken);
        }
        return taken == 0 ? new double[] {0, 0, 0}
                : new double[] {ax / taken, az / taken, ay / taken};
    }

    private static double cv(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0);
        if (mean == 0) {
            return 0;
        }
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum()
                / values.length;
        return Math.sqrt(variance) / mean;
    }

    /** Scale-free shape descriptors for one ravine. */
    private static final class Shape {
        /** Mean carved height per column — how deep the cut runs along its length. */
        double depth;
        /** Longer footprint axis over the shorter one — straight cut versus curved sprawl. */
        double elongation;
        /** Within-ravine spread of column depth — sheer slot versus flared, tapering V. */
        double wallProfile;
        /** Sideways travel per block of rise — {@code cot(dip)}. 0 is a vertical cut. */
        double lean;
        long voxels;
    }
}
