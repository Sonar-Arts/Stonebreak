package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.DistilledShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Standard shape versions (agent SD, 2026-11-19): shape 2 is the Standard generator distilled from DaedalusTGM-Exp-2.
 * Saved Standard worlds keep shape 1 (missing = {@link StandardShapeVersion#LEGACY}), new ones get shape 2; the LOD
 * cache tag tells them apart; Java's {@link DistilledShape} (terrain mapper previews) builds the same heights as the
 * native generator, for both shapes.
 */
class StandardShapeVersionTest {

    @Test
    void numbersAndDefaults() {
        assertEquals(StandardShapeVersion.V1, StandardShapeVersion.LEGACY);
        assertEquals(StandardShapeVersion.V2, StandardShapeVersion.CURRENT);
        assertEquals(StandardShapeVersion.V1, StandardShapeVersion.of(-1, StandardShapeVersion.LEGACY));
        assertEquals(StandardShapeVersion.V1, StandardShapeVersion.of(99, StandardShapeVersion.LEGACY));
        assertEquals(StandardShapeVersion.V2, StandardShapeVersion.of(2, StandardShapeVersion.LEGACY));
        assertEquals(StandardShapeVersion.CURRENT, StandardShapeVersion.orCurrent(null));
        assertFalse(StandardShapeVersion.V1.surfaceFeatures());
        assertTrue(StandardShapeVersion.V2.surfaceFeatures());
        assertEquals(HeightCurveVersion.V1, StandardShapeVersion.V1.heightCurve());
        assertEquals(HeightCurveVersion.V2, StandardShapeVersion.V2.heightCurve());
    }

    @Test
    void shapeOneConfigIsTheOldStandardConfig() {
        NativeCubeGenerator.Config plain = NativeTerrain.config(7L, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR,
            null);
        NativeCubeGenerator.Config v1 = NativeTerrain.config(7L, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR,
            null);
        NativeTerrain.applyStandardShape(v1, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, StandardShapeVersion.V1);
        assertEquals(plain.flags, v1.flags, "shape 1 adds no flags");
        assertEquals(1, v1.standardShape);
        assertNull(v1.curveRates, "shape 1 keeps the scale's v1 curve");
        NativeCubeGenerator.Config v2 = NativeTerrain.config(7L, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR,
            null);
        NativeTerrain.applyStandardShape(v2, NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, StandardShapeVersion.V2);
        assertEquals(2, v2.standardShape);
        assertEquals(32.0, v2.curveRates[0], "v2 deep-ocean rate");
        assertEquals(10.0, v2.curveKnots[2], "v2 narrow shore blend");
        assertNotEquals(0, v2.flags & NativeCubeGenerator.FLAG_SEA_FLOOR);
    }

    @Test
    void lodTagsKeepShapeOneAndNameShapeTwo() {
        assumeTrue(NativeTerrain.isAvailable(), NativeCubeGenerator.unavailableReason());
        assumeTrue(NativeCubeGenerator.standardShapeMax() >= 2, "worldgen lib predates Standard shape 2");
        try (NativeTerrain v1 = NativeTerrain.create(11L, TerrainGeneratorType.STANDARD,
                NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, null, null, null, StandardShapeVersion.V1, null);
             NativeTerrain v2 = NativeTerrain.create(11L, TerrainGeneratorType.STANDARD,
                NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, null, null, null, StandardShapeVersion.V2, null)) {
            assertEquals("native-standard-one_to_four", v1.lodCacheTag());
            assertEquals("native-standard-one_to_four-s2", v2.lodCacheTag());
            assertEquals(StandardShapeVersion.V2, v2.standardShape());
        }
    }

    /**
     * Java DistilledShape heights vs native raw heights (inside the old [1, 255) frame Java clamps to). The formula is
     * port arithmetic on both sides, but the noise is not one build: Java samples the engine's FastNoise2
     * ({@code ck_gen_grid_2d}, libcenda_kernels), the worldgen lib its own static FastNoise2 - their SIMD levels may round
     * the last float bit differently, and a floor at a block edge then flips a column (2026-11-19: shape 1, unchanged
     * code, already did). So: nearly every column equal, never more than a few blocks apart, and shape 2 no worse.
     */
    @Test
    void javaShapeMatchesNative() {
        assumeTrue(NativeTerrain.isAvailable(), NativeCubeGenerator.unavailableReason());
        assumeTrue(NativeCubeGenerator.standardShapeMax() >= 2, "worldgen lib predates Standard shape 2");
        for (StandardShapeVersion v : StandardShapeVersion.values()) {
            int compared = 0, equal = 0, maxDiff = 0;
            for (long seed : new long[] {20261119L, -77L}) {
                DistilledShape shape = new DistilledShape(seed, v);
                assertEquals(v != StandardShapeVersion.V1, shape.isV2());
                try (NativeTerrain t = NativeTerrain.create(seed, TerrainGeneratorType.STANDARD,
                        NativeCubeGenerator.TerrainScalePreset.ONE_TO_FOUR, null, null, null, v, null)) {
                    int n = 64, stride = 37;
                    int[] heights = new int[n * n];
                    int[] water = new int[n * n];
                    t.sampleRawColumns(-1200, -1300, n, stride, heights, water, null);
                    DistilledShape.Column c = new DistilledShape.Column();
                    for (int ix = 0; ix < n; ix++) {
                        for (int iz = 0; iz < n; iz++) {
                            shape.column(-1200 + ix * stride, -1300 + iz * stride, c);
                            int nat = heights[ix * n + iz];
                            if (nat <= 1 || nat >= StandardTerrain.WORLD_HEIGHT - 1) {
                                continue;   // Java clamps to the old column
                            }
                            compared++;
                            equal += nat == c.height ? 1 : 0;
                            maxDiff = Math.max(maxDiff, Math.abs(nat - c.height));
                        }
                    }
                }
            }
            System.out.printf("[standard-shape] %s: Java == native on %d of %d columns, max diff %d%n", v, equal,
                compared, maxDiff);
            assertTrue(compared > 4000, "compared " + compared);
            assertTrue(equal >= compared * 0.98, v + ": " + equal + " of " + compared + " equal");
            assertTrue(maxDiff <= 8, v + ": max diff " + maxDiff);
        }
    }
}
