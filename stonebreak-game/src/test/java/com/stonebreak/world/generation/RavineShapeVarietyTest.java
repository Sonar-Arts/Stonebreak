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
 * <p>Method: find ravines with no other ravine near enough to overlap them, carve each one on
 * its own, and reduce it to three shape descriptors that are independent of how big it is.
 * The assertion is on the <em>spread</em> of each descriptor across the sample — a coefficient
 * of variation — because that is the property being defended. A generator making one shape at
 * many sizes scores near zero on all three.
 */
public class RavineShapeVarietyTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /** How far the search sweeps, and how many isolated ravines it needs to find. */
    private static final int SEARCH_CHUNKS = 300;
    private static final int MIN_SAMPLES = 5;

    /**
     * Chunk radius that must be free of other ravines for a sample to count, and the radius
     * carved around it. A ravine reaches at most {@link RavineCarver#SCAN_RADIUS} chunks, so
     * this keeps a sample's voxels its own.
     */
    private static final int ISOLATION_CHUNKS = 10;

    /**
     * Minimum coefficient of variation (stddev / mean) per descriptor.
     *
     * <p>Set well under what the current draws produce — this is a floor against the
     * mechanism being switched off, not a target to tune against. Measured at the time of
     * writing: depth 0.16, elongation 0.32, wall profile 0.21.
     */
    private static final double MIN_DEPTH_CV = 0.10;
    private static final double MIN_ELONGATION_CV = 0.20;
    private static final double MIN_WALL_PROFILE_CV = 0.10;

    @Test
    public void ravinesDifferInShapeAndNotOnlyInSize() {
        DryHillsTileSource src = new DryHillsTileSource();
        RavineCarver carver = new RavineCarver(SEED, new HeightMapGenerator(src));

        List<Shape> shapes = new ArrayList<>();
        for (int cx = 0; cx < SEARCH_CHUNKS && shapes.size() < MIN_SAMPLES + 3; cx++) {
            for (int cz = 0; cz < SEARCH_CHUNKS && shapes.size() < MIN_SAMPLES + 3; cz++) {
                if (!carver.hasRavine(cx, cz) || !isIsolated(carver, cx, cz)) {
                    continue;
                }
                Shape shape = measure(carver, cx, cz);
                if (shape != null) {
                    shapes.add(shape);
                }
            }
        }

        assertTrue(shapes.size() >= MIN_SAMPLES, "found only " + shapes.size()
                + " isolated ravines to measure; needed " + MIN_SAMPLES);

        System.out.println("[ravines] shape descriptors per sample:");
        for (Shape s : shapes) {
            System.out.printf("  depth %.1f  elongation %.2f  wall profile %.2f  (%d voxels)%n",
                    s.depth, s.elongation, s.wallProfile, s.voxels);
        }

        double depthCv = cv(shapes.stream().mapToDouble(s -> s.depth).toArray());
        double elongationCv = cv(shapes.stream().mapToDouble(s -> s.elongation).toArray());
        double wallProfileCv = cv(shapes.stream().mapToDouble(s -> s.wallProfile).toArray());
        System.out.printf("[ravines] spread over %d samples: depth %.2f, elongation %.2f, "
                + "wall profile %.2f%n", shapes.size(), depthCv, elongationCv, wallProfileCv);

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

    private static boolean isIsolated(RavineCarver carver, int cx, int cz) {
        for (int dx = -ISOLATION_CHUNKS; dx <= ISOLATION_CHUNKS; dx++) {
            for (int dz = -ISOLATION_CHUNKS; dz <= ISOLATION_CHUNKS; dz++) {
                if ((dx != 0 || dz != 0) && carver.hasRavine(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Carves one ravine alone and reduces it to scale-free shape descriptors. */
    private static Shape measure(RavineCarver carver, int scx, int scz) {
        int r = ISOLATION_CHUNKS;
        int size = (2 * r + 1) * CHUNK;
        int[] minY = new int[size * size];
        int[] maxY = new int[size * size];
        Arrays.fill(minY, Integer.MAX_VALUE);
        Arrays.fill(maxY, -1);
        long voxels = 0;

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
                BitSet mask = carver.carveMaskForChunk(cx, cz, heights, water);
                for (int b = mask.nextSetBit(0); b >= 0; b = mask.nextSetBit(b + 1)) {
                    int gx = (dcx + r) * CHUNK + LocalBlockKey.x(b);
                    int gz = (dcz + r) * CHUNK + LocalBlockKey.z(b);
                    int i = gx * size + gz;
                    int y = LocalBlockKey.y(b);
                    minY[i] = Math.min(minY[i], y);
                    maxY[i] = Math.max(maxY[i], y);
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
        shape.voxels = voxels;
        return shape;
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
        long voxels;
    }
}
