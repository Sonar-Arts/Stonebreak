package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Headless mesher invariants: quad/skirt emission, sea-sheet height + water
 * flags, gradient normals, tree silhouettes, and the exact Y bounds carried by
 * {@link FastLodMesher.Result} (they feed per-node frustum culling — wrong
 * bounds cull visible terrain or draw hidden nodes).
 */
class FastLodMesherTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final float SEA_SURFACE_Y = SEA_LEVEL - 0.125f;
    private static final float EPS = 1e-4f;

    private FastLodMesher mesher;

    @BeforeEach
    void setUp() {
        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);
        mesher = new FastLodMesher(textures);
    }

    /** L4 data: one cell, margin-extended 3x3 heights, single surface block. */
    private static FastLodChunkData l4Data(int[] heights3x3, BlockType surface) {
        return new FastLodChunkData(FastLodKey.of(FastLodLevel.L4, 0, 0),
                heights3x3, new BlockType[]{surface}, null);
    }

    private static int[] filled(int len, int value) {
        int[] a = new int[len];
        Arrays.fill(a, value);
        return a;
    }

    @Test
    void flatLandCellEmitsSingleFlatQuad() {
        FastLodMesher.Result result = mesher.build(l4Data(filled(9, 70), BlockType.STONE));
        MmsMeshData mesh = result.mesh();

        assertEquals(4, mesh.getVertexCount());
        assertEquals(6, mesh.getIndexCount());
        // Flat terrain → zero gradient → straight-up normals.
        float[] normals = mesh.getVertexNormals();
        for (int v = 0; v < 4; v++) {
            assertEquals(0f, normals[v * 3], EPS);
            assertEquals(1f, normals[v * 3 + 1], EPS);
            assertEquals(0f, normals[v * 3 + 2], EPS);
        }
        for (float light : mesh.getLightValues()) assertEquals(1f, light, EPS);
        for (float w : mesh.getWaterHeightFlags()) assertEquals(0f, w, EPS);
        for (float a : mesh.getAlphaTestFlags()) assertEquals(0f, a, EPS);
        for (float t : mesh.getTranslucentFlags()) assertEquals(0f, t, EPS);
        assertEquals(70f, result.minY(), EPS);
        assertEquals(70f, result.maxY(), EPS);
    }

    @Test
    void skirtEmittedOnlyTowardLowerNeighbor() {
        // Center at 80; +x margin neighbor at 70; the rest at 80.
        // stride=3 layout: index = (ix+1)*3 + (iz+1); +x neighbor = (2)*3 + 1 = 7.
        int[] heights = filled(9, 80);
        heights[7] = 70;
        FastLodMesher.Result result = mesher.build(l4Data(heights, BlockType.STONE));
        MmsMeshData mesh = result.mesh();

        assertEquals(8, mesh.getVertexCount(), "top quad + exactly one skirt");
        // Skirt verts (indices 4..7) all lie on the +x edge plane (x = 16).
        float[] pos = mesh.getVertexPositions();
        for (int v = 4; v < 8; v++) {
            assertEquals(16f, pos[v * 3], EPS);
        }
        assertEquals(70f, result.minY(), EPS);
        assertEquals(80f, result.maxY(), EPS);
    }

    @Test
    void waterCellEmitsSeaSheetAtNativeSurfaceHeight() {
        // Submerged terrain: all heights below sea level, WATER surface.
        FastLodMesher.Result result = mesher.build(l4Data(filled(9, 50), BlockType.WATER));
        MmsMeshData mesh = result.mesh();

        assertEquals(4, mesh.getVertexCount(), "flat sea sheet, no skirts");
        float[] pos = mesh.getVertexPositions();
        for (int v = 0; v < 4; v++) {
            assertEquals(SEA_SURFACE_Y, pos[v * 3 + 1], EPS,
                    "sheet must match the native water surface (block base + 0.875)");
        }
        // Water flag routes these verts into the world shader's distant-water branch.
        for (float w : mesh.getWaterHeightFlags()) assertEquals(1f, w, EPS);
        // Sea sheets keep flat-up normals — the shader perturbs procedurally.
        float[] normals = mesh.getVertexNormals();
        for (int v = 0; v < 4; v++) assertEquals(1f, normals[v * 3 + 1], EPS);
        assertEquals(SEA_SURFACE_Y, result.minY(), EPS);
        assertEquals(SEA_SURFACE_Y, result.maxY(), EPS);
    }

    @Test
    void coastalSkirtEndsAtSeaSurface() {
        // Land cliff at 80 dropping into a submerged (-> sea sheet) neighbor:
        // the skirt bottom must meet the sheet at 63.875, not int SEA_LEVEL —
        // a bottom at 64.0 would leave a 0.125 sliver gap above the sheet.
        int[] heights = filled(9, 80);
        heights[7] = 50;   // +x neighbor far below sea level
        FastLodMesher.Result result = mesher.build(l4Data(heights, BlockType.GRASS));
        MmsMeshData mesh = result.mesh();

        assertEquals(8, mesh.getVertexCount());
        float[] pos = mesh.getVertexPositions();
        float skirtBottom = Float.MAX_VALUE;
        for (int v = 4; v < 8; v++) {
            skirtBottom = Math.min(skirtBottom, pos[v * 3 + 1]);
        }
        assertEquals(SEA_SURFACE_Y, skirtBottom, EPS);
        // Land cell → its skirt is not water-flagged.
        for (float w : mesh.getWaterHeightFlags()) assertEquals(0f, w, EPS);
        assertEquals(SEA_SURFACE_Y, result.minY(), EPS);
    }

    @Test
    void topQuadNormalsFollowHeightGradient() {
        // L2 (cellSize=4, 4x4 cells, stride=6) with height rising 4 per cell in
        // +x → slope of exactly 1 block per block. Every corner's central
        // difference gives gx=1, gz=0 → n = normalize(-1, 1, 0). Heights stay
        // above sea level so no sea-sheet/skirt clamping interferes.
        FastLodLevel level = FastLodLevel.L2;
        int[] heights = new int[level.heightCount()];
        for (int hx = 0; hx < level.stride(); hx++) {
            for (int hz = 0; hz < level.stride(); hz++) {
                heights[hx * level.stride() + hz] = 80 + 4 * (hx - 1);
            }
        }
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.STONE);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, null);

        MmsMeshData mesh = mesher.build(data).mesh();
        float expected = (float) (1.0 / Math.sqrt(2.0));
        float[] normals = mesh.getVertexNormals();
        // The slope makes every cell emit a -x skirt too; skirt normals are
        // axis-aligned so they never match the gradient normal. Count the
        // gradient-normal verts: exactly the 4 corners of every top quad.
        int gradientVerts = 0;
        for (int v = 0; v < mesh.getVertexCount(); v++) {
            float nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
            if (Math.abs(ny - expected) < 1e-3f) {
                assertEquals(-expected, nx, 1e-3f);
                assertEquals(0f, nz, 1e-3f);
                gradientVerts++;
            }
        }
        assertEquals(level.cellCount() * 4, gradientVerts,
                "every top-quad vertex carries the slope normal");
    }

    @Test
    void treeSilhouetteEmittedAndBoundsIncludeCanopy() {
        FastLodLevel level = FastLodLevel.L0;
        int[] heights = filled(level.heightCount(), 70);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.GRASS);
        TreeSample[] trees = new TreeSample[level.cellCount()];
        trees[2 * level.cellsPerAxis() + 3] = new TreeSample(TreeKind.OAK, 4);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, trees);

        FastLodMesher.Result result = mesher.build(data);
        // 256 flat top quads + 9 tree quads (4 trunk + 5 canopy).
        assertEquals((256 + 9) * 4, result.mesh().getVertexCount());
        // Canopy top: trunkTop(74) + 1 + CANOPY_HALF_HEIGHT(1.5).
        assertEquals(76.5f, result.maxY(), EPS);
        assertEquals(70f, result.minY(), EPS);
    }

    @Test
    void treeOnWaterCellIsSuppressed() {
        FastLodLevel level = FastLodLevel.L0;
        int[] heights = filled(level.heightCount(), 50);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.WATER);
        TreeSample[] trees = new TreeSample[level.cellCount()];
        trees[0] = new TreeSample(TreeKind.PINE, 6);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, trees);

        FastLodMesher.Result result = mesher.build(data);
        assertEquals(256 * 4, result.mesh().getVertexCount(), "sea sheets only, no tree");
        assertEquals(SEA_SURFACE_Y, result.maxY(), EPS);
    }

    @Test
    void boundsMatchHandComputedExtremes() {
        // Mixed terrain: peak 120, valley 30 (below sea → sheet at 63.875 with
        // WATER surface), flat 70 elsewhere. minY comes from the peak's skirt
        // descending to the sea sheet — never from raw terrain below the sheet.
        FastLodLevel level = FastLodLevel.L3;   // 2x2 cells, stride 4
        int[] heights = filled(level.heightCount(), 70);
        // Interior cells: (0,0)=120, (1,1)=30, others 70.
        heights[(0 + 1) * 4 + (0 + 1)] = 120;
        heights[(1 + 1) * 4 + (1 + 1)] = 30;
        BlockType[] surface = new BlockType[]{
                BlockType.STONE, BlockType.STONE,
                BlockType.STONE, BlockType.WATER};
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, null);

        FastLodMesher.Result result = mesher.build(data);
        assertEquals(120f, result.maxY(), EPS);
        assertEquals(SEA_SURFACE_Y, result.minY(), EPS);
        assertTrue(result.mesh().getVertexCount() > 0);
    }
}
