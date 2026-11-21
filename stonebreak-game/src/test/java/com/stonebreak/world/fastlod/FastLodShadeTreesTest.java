package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.NativeCubeGenerator;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.WorldFeatureVersion;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Shade's dry oaks in far terrain (FastLOD): the fine-band column samples and the coarse levels' tree spots of a
 * world created with The Shade (WorldFeatureVersion V5, the current Standard shape with its vegetation channels) carry
 * DRY_OAK on the Shade's dry grass and nowhere else; a spot keeps its kind and trunk through the FastLOD node format,
 * and the mesher draws the dry oak's crown as an oak's.
 */
class FastLodShadeTreesTest {

    private static final long SEED = 31337L;

    private static NativeTerrain world() {
        return NativeTerrain.create(SEED, TerrainGeneratorType.STANDARD, TerrainScalePreset.ONE_TO_FOUR,
                ClimateLayout.DEFAULT, null, WorldFeatureVersion.V5, null, null);
    }

    /** A Shade column (only the Shade lays dry grass), chunk-aligned: {x, z}. */
    private static int[] shadeSpot(NativeTerrain t) {
        int count = 48, stride = 256, origin = -count * stride / 2, n = count * count;
        BlockType[] surface = new BlockType[n];
        t.sampleRawColumns(origin, origin, count, stride, new int[n], new int[n], surface);
        for (int i = 0; i < n; i++) {
            if (surface[i] == BlockType.DRY_GRASS) {
                int x = origin + (i / count) * stride, z = origin + (i % count) * stride;   // [ix * count + iz]
                return new int[]{Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16};
            }
        }
        throw new AssertionError("seed " + SEED + " has no Shade");
    }

    @Test
    void fineBandSamplesGrowDryOaksOnTheShadesGround() {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        try (NativeTerrain t = world()) {
            int[] spot = shadeSpot(t);
            for (int stride : new int[]{1, 2, 4}) {
                int count = 64, n = count * count;
                BlockType[] surface = new BlockType[n];
                TreeSample[] trees = new TreeSample[n];
                t.sampleColumns(spot[0] - count / 2 * stride, spot[1] - count / 2 * stride, count, stride,
                        new int[n], new int[n], surface, trees);
                int dryOaks = 0;
                for (int i = 0; i < n; i++) {
                    if (trees[i] == null) continue;
                    boolean shade = surface[i] == BlockType.DRY_GRASS;
                    assertEquals(shade, trees[i].kind() == TreeKind.DRY_OAK, "stride " + stride + ": dry oaks on dry grass only");
                    dryOaks += trees[i].kind() == TreeKind.DRY_OAK ? 1 : 0;
                }
                assertTrue(dryOaks > 0, "stride " + stride + ": the Shade is wooded in the LOD samples");
            }
        }
    }

    @Test
    void coarseTreeSpotsCarryDryOaksThroughTheNodeFormat() {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: " + NativeCubeGenerator.unavailableReason());
        try (NativeTerrain t = world()) {
            int[] spot = shadeSpot(t);
            int cells = 8, cellSize = 8, n = cells * cells;
            int dryOaks = 0;
            for (int dcx = -4; dcx <= 4; dcx += 4) {
                for (int dcz = -4; dcz <= 4; dcz += 4) {
                    int chunkX = spot[0] / 16 + dcx, chunkZ = spot[1] / 16 + dcz;
                    int[] h = new int[n], w = new int[n];
                    BlockType[] surface = new BlockType[n];
                    t.sampleColumns(chunkX * 16, chunkZ * 16, cells, cellSize, h, w, surface, null);
                    int[] spots = t.probeCellTrees(chunkX, chunkZ, cells, cellSize, h, w, surface);
                    for (int s : spots) {
                        TreeKind kind = FastLodChunkData.spotKind(s);
                        if (kind != TreeKind.DRY_OAK) continue;
                        dryOaks++;
                        assertEquals(5, FastLodChunkData.spotTrunk(s), "a dry oak's trunk survives the 3-bit kind");
                    }
                }
            }
            assertTrue(dryOaks > 0, "the coarse levels plant dry oaks in the Shade");
        }
    }

    @Test
    void aDryOakSpotRoundTripsAndDrawsAnOaksCrown() {
        for (int trunk : new int[]{0, 5, 31}) {
            int s = FastLodChunkData.packTreeSpot(9, 14, TreeKind.DRY_OAK, trunk);
            assertEquals(9, FastLodChunkData.spotX(s));
            assertEquals(14, FastLodChunkData.spotZ(s));
            assertEquals(TreeKind.DRY_OAK, FastLodChunkData.spotKind(s));
            assertEquals(trunk, FastLodChunkData.spotTrunk(s));
        }
        assertArrayEquals(FastLodMesher.canopyTiers(TreeKind.OAK, 5), FastLodMesher.canopyTiers(TreeKind.DRY_OAK, 5));
        assertEquals(BlockType.DRY_OAK_LOG, TreeKind.DRY_OAK.trunkBlock());
        assertEquals(BlockType.DRY_OAK_LEAVES, TreeKind.DRY_OAK.leavesBlock());
    }
}
