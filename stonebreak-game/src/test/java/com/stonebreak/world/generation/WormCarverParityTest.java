package com.stonebreak.world.generation;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.heightmap.CarveMaskKey;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The coverage the native worm-carver port needs and no other test provides: the
 * Java {@link PerlinWormCarver} walk bit-for-bit against {@code CendaKernels.carveWorms}
 * on the same seed. {@code FusedChunkGenParityTest} compares C++ walker vs C++ walker
 * (both of its paths run the kernel), and the cave tests supply a height oracle that
 * disables the native carver context — so before this test, nothing failed if the two
 * backends carved different tunnels (GitHub issue #244).
 *
 * <p>The kernel builds its WaterGuard plane internally, so the Java side of the
 * comparison is the guarded 4-arg overload — the exact mask
 * {@code TerrainGenerationSystem.nativeWormMask} falls back to when the kernel fails.
 * The target set includes a chunk where the guard actually suppresses carving, so an
 * unguarded fallback (GitHub issue #248) cannot pass on dry chunks alone.
 *
 * <p>Native-gated: skips without the Cenda kernels or on the Java noise backend, exactly
 * like {@code FusedChunkGenParityTest}. There is no headless substitute — the thing under
 * test <em>is</em> the native walker.
 */
class WormCarverParityTest {

    private static final long SEED = 424242L;

    /** Worm-bearing target chunks to compare (the primary worm walks), plus worm-free ones. */
    private static final int WORM_TARGETS = 5;
    private static final int WORM_FREE_TARGETS = 2;
    private static final int SCAN_LIMIT = 24;
    /** Search bound for a worm chunk whose carve the water guard trims (ocean nearby). */
    private static final int WET_SCAN_LIMIT = 96;

    @Test
    void javaWalkMatchesNativeKernel() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        assumeTrue(TerrainNoise.backend() == TerrainNoise.Backend.NATIVE, "native backend inactive");

        HeightMapGenerator heightMap = new HeightMapGenerator(new NoiseRouter(SEED));
        PerlinWormCarver javaCarver = new PerlinWormCarver(SEED, heightMap);

        long ctx = NoiseRouter.createCarverTerrainContext(SEED,
            HeightMapGenerator.splineXs(), HeightMapGenerator.splineYs(),
            HeightMapGenerator.splineSizes(), HeightMapGenerator.DETAIL_AMPLITUDE);
        try {
            List<int[]> targets = pickTargets(javaCarver);
            assertFalse(targets.isEmpty(), "seed " + SEED + " found no worm chunks in the scanned region");
            int[] wetTarget = findWetTarget(javaCarver, heightMap);
            assertNotNull(wetTarget, "seed " + SEED + " found no worm chunk the water guard trims within "
                + WET_SCAN_LIMIT + " chunks — the guard is untested");
            targets.add(wetTarget);

            int scanRadius = PerlinWormCarver.scanRadius();
            for (int[] target : targets) {
                int chunkX = target[0];
                int chunkZ = target[1];
                int[] chunkHeights = new int[256];
                int[] waterLevels = new int[256];
                heightMap.populateChunkHeights(chunkX, chunkZ, chunkHeights, waterLevels);

                BitSet javaMask = javaCarver.carveMaskForChunk(chunkX, chunkZ, chunkHeights, waterLevels);

                int[] anchorData = collectAnchors(javaCarver, chunkX, chunkZ, scanRadius);
                int[] anchorChunks = anchorData.length == 0 ? null : anchorData;
                float[] anchors = anchorChunks == null ? null : collectAnchorPoints(javaCarver, chunkX, chunkZ, scanRadius, anchorChunks);

                long[] mask = new long[1024];
                long carved = CendaKernels.carveWorms(ctx, chunkX, chunkZ, chunkHeights,
                    anchorChunks, anchors, mask);
                assumeTrue(carved >= 0, "kernel carveWorms failed (ABI mismatch? rebuild kernels)");
                BitSet nativeMask = BitSet.valueOf(mask);

                assertEquals(javaMask, nativeMask, () -> describeDivergence(chunkX, chunkZ, javaMask, nativeMask));
            }
        } finally {
            CendaKernels.terrainDestroy(ctx);
        }
    }

    /**
     * Deterministically enumerates target chunks for the seed: {@link #WORM_TARGETS}
     * worm-bearing chunks (where the primary worm walks) plus {@link #WORM_FREE_TARGETS}
     * worm-free ones (neighbour-scan and empty-mask coverage). Pure hash predicate via
     * {@link PerlinWormCarver#hasWormAt} — same target set every run.
     */
    private static List<int[]> pickTargets(PerlinWormCarver carver) {
        List<int[]> targets = new ArrayList<>();
        List<int[]> wormFree = new ArrayList<>();
        for (int chunkX = 0; chunkX < SCAN_LIMIT; chunkX++) {
            for (int chunkZ = 0; chunkZ < SCAN_LIMIT; chunkZ++) {
                if (carver.hasWormAt(chunkX, chunkZ)) {
                    if (targets.size() < WORM_TARGETS) {
                        targets.add(new int[]{chunkX, chunkZ});
                    }
                } else if (wormFree.size() < WORM_FREE_TARGETS) {
                    wormFree.add(new int[]{chunkX, chunkZ});
                }
            }
        }
        targets.addAll(wormFree);
        return targets;
    }

    /**
     * First worm-bearing chunk (row-major from the origin) whose guarded
     * mask differs from its unguarded one — i.e. a chunk where WaterGuard has work to do.
     * Null if the seed has none within {@link #WET_SCAN_LIMIT}.
     */
    private static int[] findWetTarget(PerlinWormCarver carver, HeightMapGenerator heightMap) {
        int[] heights = new int[256];
        int[] waterLevels = new int[256];
        for (int chunkX = 0; chunkX < WET_SCAN_LIMIT; chunkX++) {
            for (int chunkZ = 0; chunkZ < WET_SCAN_LIMIT; chunkZ++) {
                if (!carver.hasWormAt(chunkX, chunkZ)) {
                    continue;
                }
                heightMap.populateChunkHeights(chunkX, chunkZ, heights, waterLevels);
                boolean wet = false;
                for (int level : waterLevels) {
                    if (level != WorldConfiguration.NO_WATER) {
                        wet = true;
                        break;
                    }
                }
                if (!wet) {
                    continue;
                }
                BitSet guarded = carver.carveMaskForChunk(chunkX, chunkZ, heights, waterLevels);
                BitSet unguarded = carver.carveMaskForChunk(chunkX, chunkZ, heights);
                if (!guarded.equals(unguarded)) {
                    return new int[]{chunkX, chunkZ};
                }
            }
        }
        return null;
    }

    /**
     * Cavern-connector anchor source chunks in the scan radius, precomputed by the Java
     * cavern carvers exactly as {@code TerrainGenerationSystem.nativeWormMask} does —
     * cavern placement stays owned by the Java carvers, so the kernel receives them
     * as inputs. Empty array = no anchors (passed to the kernel as null).
     */
    private int[] collectAnchors(PerlinWormCarver carver, int chunkX, int chunkZ, int scanRadius) {
        List<int[]> chunks = new ArrayList<>();
        for (int dcx = -scanRadius; dcx <= scanRadius; dcx++) {
            for (int dcz = -scanRadius; dcz <= scanRadius; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!carver.hasWormAt(srcCx, srcCz)) {
                    continue;
                }
                if (carver.cavernAnchorFor(srcCx, srcCz) != null) {
                    chunks.add(new int[]{srcCx, srcCz});
                }
            }
        }
        int[] anchorChunks = new int[chunks.size() * 2];
        for (int i = 0; i < chunks.size(); i++) {
            anchorChunks[i * 2] = chunks.get(i)[0];
            anchorChunks[i * 2 + 1] = chunks.get(i)[1];
        }
        return anchorChunks;
    }

    /** Anchor points co-located with {@link #collectAnchors}' source chunks, [x, y, z] triples. */
    private float[] collectAnchorPoints(PerlinWormCarver carver, int chunkX, int chunkZ, int scanRadius, int[] anchorChunks) {
        List<float[]> anchorList = new ArrayList<>();
        for (int i = 0; i < anchorChunks.length; i += 2) {
            float[] anchor = carver.cavernAnchorFor(anchorChunks[i], anchorChunks[i + 1]);
            if (anchor != null) {
                anchorList.add(anchor);
            }
        }
        float[] anchors = new float[anchorList.size() * 3];
        for (int i = 0; i < anchorList.size(); i++) {
            float[] anchor = anchorList.get(i);
            anchors[i * 3] = anchor[0];
            anchors[i * 3 + 1] = anchor[1];
            anchors[i * 3 + 2] = anchor[2];
        }
        return anchors;
    }

    /**
     * Direct diagnosis on failure: chunk coords, carve-bit counts, and the first
     * divergent block position decoded via {@link CarveMaskKey} — the layout shared
     * by the Java carvers and the kernel's {@code long[1024]} mask ABI.
     */
    private static String describeDivergence(int chunkX, int chunkZ, BitSet javaMask, BitSet nativeMask) {
        BitSet clone = (BitSet) nativeMask.clone();
        clone.xor(javaMask);
        int first = clone.nextSetBit(0);
        return "worm carve masks diverge for chunk (" + chunkX + ", " + chunkZ + "): java "
            + javaMask.cardinality() + " bits, native " + nativeMask.cardinality() + " bits"
            + (first < 0 ? "" : ", first divergent bit " + first + " = block ("
                + CarveMaskKey.x(first) + ", " + CarveMaskKey.y(first) + ", " + CarveMaskKey.z(first) + ")");
    }
}
