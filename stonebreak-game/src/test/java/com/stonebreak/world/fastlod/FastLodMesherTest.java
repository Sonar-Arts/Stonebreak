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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Headless mesher invariants: terrain quad/skirt emission (seabed included —
 * submerged cells are regular terrain now), the separate translucent
 * water-sheet mesh and its water.vert flag contract, gradient normals, tree
 * silhouettes, node-border foundation walls, and the exact combined Y bounds
 * carried by {@link FastLodMesher.Result} (they feed per-node frustum
 * culling — wrong bounds cull visible terrain or draw hidden nodes).
 *
 * <p>Geometry counting notes: every cell edge on the node border emits a dark
 * foundation wall down to y=0 (crack backing + underground sightline seal), so
 * an L4 node (one cell) always carries 4 foundation quads and an L{n} node
 * carries 4 × cellsPerAxis. Foundation verts are the ones with light == 0.
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

    private static int countVertsWithLight(MmsMeshData mesh, float lightVal) {
        int n = 0;
        for (float l : mesh.getLightValues()) {
            if (Math.abs(l - lightVal) < EPS) n++;
        }
        return n;
    }

    @Test
    void flatLandCellEmitsFlatQuadPlusBorderFoundations() {
        FastLodMesher.Result result = mesher.build(l4Data(filled(9, 326), BlockType.STONE));
        MmsMeshData mesh = result.mesh();

        // Top quad + 4 border foundation walls (326 → 0), no skirts, no water.
        assertEquals(20, mesh.getVertexCount());
        assertEquals(30, mesh.getIndexCount());
        assertNull(result.waterMesh(), "dry node has no water sheet");
        // Flat terrain → zero gradient → straight-up normals on the top quad.
        float[] normals = mesh.getVertexNormals();
        for (int v = 0; v < 4; v++) {
            assertEquals(0f, normals[v * 3], EPS);
            assertEquals(1f, normals[v * 3 + 1], EPS);
            assertEquals(0f, normals[v * 3 + 2], EPS);
        }
        assertEquals(4, countVertsWithLight(mesh, 1f), "only the top quad is lit");
        assertEquals(16, countVertsWithLight(mesh, 0f), "4 dark foundation walls");
        for (float w : mesh.getWaterHeightFlags()) assertEquals(0f, w, EPS);
        for (float a : mesh.getAlphaTestFlags()) assertEquals(0f, a, EPS);
        for (float t : mesh.getTranslucentFlags()) assertEquals(0f, t, EPS);
        assertEquals(0f, result.minY(), EPS, "foundations reach y=0");
        assertEquals(326f, result.maxY(), EPS);
    }

    @Test
    void skirtEmittedOnlyTowardLowerNeighbor() {
        // Center at 336 (=80 above the old SEA_LEVEL=64, shifted +256 to sit
        // the same distance above the new SEA_LEVEL=320); +x margin neighbor
        // at 326 (=70, same shift); the rest at 336.
        // stride=3 layout: index = (ix+1)*3 + (iz+1); +x neighbor = (2)*3 + 1 = 7.
        int[] heights = filled(9, 336);
        heights[7] = 326;
        FastLodMesher.Result result = mesher.build(l4Data(heights, BlockType.STONE));
        MmsMeshData mesh = result.mesh();

        // Top + one lit skirt + 4 border foundations.
        assertEquals(24, mesh.getVertexCount(), "top quad + one skirt + 4 foundations");
        assertEquals(8, countVertsWithLight(mesh, 1f), "top + exactly one lit skirt");
        // Skirt verts (emitted right after the top quad, indices 4..7) lie on
        // the +x edge plane (x = 16) and span exactly 80 → 70; the foundation
        // continues 70 → 0 separately.
        float[] pos = mesh.getVertexPositions();
        float skirtTop = Float.MIN_VALUE, skirtBottom = Float.MAX_VALUE;
        for (int v = 4; v < 8; v++) {
            assertEquals(16f, pos[v * 3], EPS);
            skirtTop = Math.max(skirtTop, pos[v * 3 + 1]);
            skirtBottom = Math.min(skirtBottom, pos[v * 3 + 1]);
        }
        assertEquals(336f, skirtTop, EPS);
        assertEquals(326f, skirtBottom, EPS);
        assertEquals(0f, result.minY(), EPS);
        assertEquals(336f, result.maxY(), EPS);
    }

    @Test
    void submergedCellEmitsSeabedTerrainAndWaterSheet() {
        // Fully submerged flat node: the terrain mesh is the SEABED at the
        // real height (textured with the sampled seabed block), and the water
        // surface is a separate translucent sheet mesh at the native water
        // plane, drawn by the dedicated water renderer.
        FastLodMesher.Result result = mesher.build(l4Data(filled(9, 306), BlockType.SAND));

        MmsMeshData terrain = result.mesh();
        assertEquals(20, terrain.getVertexCount(), "seabed top + 4 foundations, no skirts");
        float[] pos = terrain.getVertexPositions();
        for (int v = 0; v < 4; v++) {
            assertEquals(306f, pos[v * 3 + 1], EPS, "seabed sits at the real terrain height");
        }
        // Terrain mesh carries no water flags at all anymore.
        for (float w : terrain.getWaterHeightFlags()) assertEquals(0f, w, EPS);

        MmsMeshData sheet = result.waterMesh();
        assertNotNull(sheet);
        assertEquals(4, sheet.getVertexCount(), "one sheet quad per submerged cell");
        float[] wpos = sheet.getVertexPositions();
        for (int v = 0; v < 4; v++) {
            assertEquals(SEA_SURFACE_Y, wpos[v * 3 + 1], EPS,
                    "sheet must match the native water surface (block base + 0.875)");
        }
        // Combined bounds: sheet above the seabed, foundations down to 0.
        assertEquals(0f, result.minY(), EPS);
        assertEquals(SEA_SURFACE_Y, result.maxY(), EPS);
    }

    @Test
    void waterSheetFlagsFollowWaterVertContract() {
        // water.vert semantics: flags.x = surface-height fraction (0.875),
        // flags.y = falling (0), flags.w = light (1); normals straight up.
        FastLodMesher.Result result = mesher.build(l4Data(filled(9, 296), BlockType.SAND));
        MmsMeshData sheet = result.waterMesh();
        assertNotNull(sheet);
        for (float f : sheet.getWaterHeightFlags()) assertEquals(0.875f, f, EPS);
        for (float f : sheet.getAlphaTestFlags()) assertEquals(0f, f, EPS);
        for (float f : sheet.getLightValues()) assertEquals(1f, f, EPS);
        float[] normals = sheet.getVertexNormals();
        for (int v = 0; v < 4; v++) {
            assertEquals(1f, normals[v * 3 + 1], EPS);
        }
    }

    @Test
    void coastalCliffSkirtDescendsToRealSeabed() {
        // Land cliff at 336 dropping into a submerged neighbor at 306: the skirt
        // follows the REAL heights (336 → 306) — the seabed is ordinary terrain
        // now, and the neighbor node's water sheet blends over it exactly like
        // native water over a native shore.
        int[] heights = filled(9, 336);
        heights[7] = 306;   // +x neighbor far below sea level
        FastLodMesher.Result result = mesher.build(l4Data(heights, BlockType.GRASS));
        MmsMeshData mesh = result.mesh();

        assertEquals(24, mesh.getVertexCount(), "top + coastal skirt + 4 foundations");
        assertNull(result.waterMesh(), "the cell itself is dry — no sheet from this node");
        float[] pos = mesh.getVertexPositions();
        float skirtBottom = Float.MAX_VALUE;
        for (int v = 4; v < 8; v++) {
            skirtBottom = Math.min(skirtBottom, pos[v * 3 + 1]);
        }
        assertEquals(306f, skirtBottom, EPS);
        assertEquals(0f, result.minY(), EPS);
    }

    @Test
    void foundationWallsOnlyOnNodeBorderEdges() {
        // L2 (4x4 cells): flat land — no skirts anywhere, so every dark vert
        // belongs to a border foundation. 4 edges × 4 cells = 16 walls; the
        // interior cell edges emit nothing.
        FastLodLevel level = FastLodLevel.L2;
        int[] heights = filled(level.heightCount(), 326);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.STONE);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, null);

        FastLodMesher.Result result = mesher.build(data);
        MmsMeshData mesh = result.mesh();
        assertEquals((16 + 16) * 4, mesh.getVertexCount(), "16 tops + 16 foundations");
        assertEquals(16 * 4, countVertsWithLight(mesh, 0f));

        // Every foundation wall spans its cell top (326) down to exactly 0 and
        // sits on the node's outer boundary planes (x/z == 0 or 16).
        float[] pos = mesh.getVertexPositions();
        float[] light = mesh.getLightValues();
        for (int v = 0; v < mesh.getVertexCount(); v++) {
            if (Math.abs(light[v]) > EPS) continue;
            float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
            assertTrue(y == 0f || y == 326f, "foundation verts at top or y=0, got " + y);
            boolean onBoundary = x == 0f || x == 16f || z == 0f || z == 16f;
            assertTrue(onBoundary, "foundation off the node boundary at " + x + "," + z);
        }
        assertEquals(0f, result.minY(), EPS);
    }

    @Test
    void topQuadNormalsFollowHeightGradient() {
        // L2 (cellSize=4, 4x4 cells, stride=6) with height rising 4 per cell in
        // +x → slope of exactly 1 block per block. Every corner's central
        // difference gives gx=1, gz=0 → n = normalize(-1, 1, 0). Heights stay
        // above sea level so no water sheet interferes with counting.
        FastLodLevel level = FastLodLevel.L2;
        int[] heights = new int[level.heightCount()];
        for (int hx = 0; hx < level.stride(); hx++) {
            for (int hz = 0; hz < level.stride(); hz++) {
                heights[hx * level.stride() + hz] = 336 + 4 * (hx - 1);
            }
        }
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.STONE);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, null);

        MmsMeshData mesh = mesher.build(data).mesh();
        float expected = (float) (1.0 / Math.sqrt(2.0));
        float[] normals = mesh.getVertexNormals();
        // The slope makes every cell emit a -x skirt too; skirt and foundation
        // normals are axis-aligned so they never match the gradient normal.
        // Count the gradient-normal verts: exactly the 4 corners of every top quad.
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
        int[] heights = filled(level.heightCount(), 326);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.GRASS);
        TreeSample[] trees = new TreeSample[level.cellCount()];
        trees[2 * level.cellsPerAxis() + 3] = new TreeSample(TreeKind.OAK, 4);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, trees);

        FastLodMesher.Result result = mesher.build(data);
        // 256 flat top quads + 9 tree quads (4 trunk + 5 canopy)
        // + 4 × 16 border foundations.
        assertEquals((256 + 9 + 64) * 4, result.mesh().getVertexCount());
        // Canopy top: trunkTop(330) + 1 + CANOPY_HALF_HEIGHT(1.5).
        assertEquals(332.5f, result.maxY(), EPS);
        assertEquals(0f, result.minY(), EPS);
    }

    @Test
    void treeOnSubmergedCellIsSuppressed() {
        FastLodLevel level = FastLodLevel.L0;
        int[] heights = filled(level.heightCount(), 306);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.SAND);
        TreeSample[] trees = new TreeSample[level.cellCount()];
        trees[0] = new TreeSample(TreeKind.PINE, 6);
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, trees);

        FastLodMesher.Result result = mesher.build(data);
        assertEquals((256 + 64) * 4, result.mesh().getVertexCount(),
                "seabed tops + border foundations only, no tree");
        assertEquals(256 * 4, result.waterMesh().getVertexCount(), "full sheet cover");
        assertEquals(SEA_SURFACE_Y, result.maxY(), EPS, "sheet above the flat seabed");
    }

    @Test
    void boundsMatchHandComputedExtremes() {
        // Mixed terrain: peak 376 (=120 shifted +256), submerged valley 286
        // (=30 shifted; seabed + sheet), flat 326 (=70 shifted) elsewhere.
        // maxY comes from the peak; minY is 0 because every border cell drops
        // a foundation wall to bedrock.
        FastLodLevel level = FastLodLevel.L3;   // 2x2 cells, stride 4
        int[] heights = filled(level.heightCount(), 326);
        // Interior cells: (0,0)=376, (1,1)=286, others 326.
        heights[(0 + 1) * 4 + (0 + 1)] = 376;
        heights[(1 + 1) * 4 + (1 + 1)] = 286;
        BlockType[] surface = new BlockType[]{
                BlockType.STONE, BlockType.STONE,
                BlockType.STONE, BlockType.SAND};
        FastLodChunkData data = new FastLodChunkData(
                FastLodKey.of(level, 0, 0), heights, surface, null);

        FastLodMesher.Result result = mesher.build(data);
        assertEquals(376f, result.maxY(), EPS);
        assertEquals(0f, result.minY(), EPS);
        assertNotNull(result.waterMesh(), "one submerged cell → sheet quad");
        assertEquals(4, result.waterMesh().getVertexCount());
        assertTrue(result.mesh().getVertexCount() > 0);
    }
}
