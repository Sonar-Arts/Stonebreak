package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the water-mesh split: water geometry builds into
 * {@link ChunkMeshResult#waterMesh()} (consumed by the dedicated water
 * renderer) and never into the atlas mesh, with the water-specific vertex
 * semantics (flags.x = surface height, flags.y = falling, flags.z = source)
 * and water face-culling rules intact.
 *
 * <p>Fully headless — the real {@code MmsCcoAdapter} over a hand-built chunk,
 * no OpenGL (same pattern as {@code TerrainMeshConsistencyTest}).
 *
 * <p>Layout of the hand-built chunk (floor of STONE at y=10, all scenarios
 * kept off chunk borders — unloaded neighbors cull border faces):
 * <ul>
 *   <li>(2,11,2): isolated SOURCE cell</li>
 *   <li>(5,11,5): FLOWING cell, level 3</li>
 *   <li>(8,11..13,8): FALLING column (3 cells)</li>
 *   <li>(11,11,2): SOURCE cell with a STONE wall to its east (12,11,2)</li>
 *   <li>(2,11,12)+(3,11,12): adjacent SOURCE pair (no face between them)</li>
 * </ul>
 */
public class WaterMeshSplitTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final float MAX_WATER_HEIGHT = 0.875f;
    private static final float EPS = 1e-3f;

    private TestWorld world;
    private com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter adapter;
    private Chunk chunk;

    @BeforeEach
    void setUp() {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        adapter = new com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter(
                new StubTextureMapper(), world);

        chunk = new Chunk(0, 0);
        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                chunk.setBlock(lx, 10, lz, BlockType.STONE);
            }
        }
        // Isolated source.
        chunk.setBlock(2, 11, 2, BlockType.WATER);
        // Flowing cell, level 3.
        chunk.setBlock(5, 11, 5, BlockType.WATER);
        chunk.getWaterLayer().set(5, 11, 5, 3);
        // Falling column.
        for (int y = 11; y <= 13; y++) {
            chunk.setBlock(8, y, 8, BlockType.WATER);
            chunk.getWaterLayer().set(8, y, 8, ChunkWaterLayer.FALLING);
        }
        // Source with a stone wall to its east.
        chunk.setBlock(11, 11, 2, BlockType.WATER);
        chunk.setBlock(12, 11, 2, BlockType.STONE);
        // Adjacent source pair.
        chunk.setBlock(2, 11, 12, BlockType.WATER);
        chunk.setBlock(3, 11, 12, BlockType.WATER);

        world.setChunk(0, 0, chunk);
    }

    private ChunkMeshResult buildMesh() {
        return adapter.generateChunkMesh(new ChunkDataView(chunk),
                chunk.getCcoStateManager(), chunk.getCcoDirtyTracker());
    }

    @Test
    void waterGeometryGoesToWaterMeshOnly() {
        ChunkMeshResult result = buildMesh();

        assertTrue(result.hasWaterMesh(), "chunk with water must produce a water mesh");
        MmsMeshData atlas = result.atlasMesh();
        assertFalse(atlas.isEmpty(), "stone floor must produce an atlas mesh");

        // The hand-built chunk contains only full cubes besides water, so every
        // atlas vertex must sit on integer Y. Water's 0.875-height surface
        // vertices are fractional — any fractional atlas Y means water leaked
        // into the atlas mesh.
        float[] atlasPos = atlas.getVertexPositions();
        for (int v = 0; v < atlas.getVertexCount(); v++) {
            float y = atlasPos[v * 3 + 1];
            assertTrue(Math.abs(y - Math.round(y)) < EPS,
                    "atlas mesh contains fractional-height vertex y=" + y + " (water leaked into atlas)");
        }

        // The water mesh must carry the 7/8-height source surface.
        assertTrue(anyVertexAtHeight(result.waterMesh(), 11 + MAX_WATER_HEIGHT),
                "water mesh must contain source-surface vertices at y=11.875");
    }

    @Test
    void sourceCellTopCornersPinnedToSourceHeight() {
        MmsMeshData water = buildMesh().waterMesh();
        // Top face of the isolated source at (2,11,2): all 4 corners exactly 11.875.
        boolean found = false;
        for (int q = 0; q + 4 <= water.getVertexCount(); q += 4) {
            if (!isTopFaceOverCell(water, q, 2, 2)) {
                continue;
            }
            found = true;
            float[] pos = water.getVertexPositions();
            for (int v = q; v < q + 4; v++) {
                assertEquals(11 + MAX_WATER_HEIGHT, pos[v * 3 + 1], EPS,
                        "isolated source top corner must be pinned to 0.875");
            }
        }
        assertTrue(found, "isolated source cell must emit a top face");
    }

    @Test
    void flowingCellSurfaceLowerThanSource() {
        MmsMeshData water = buildMesh().waterMesh();
        // Level-3 flowing cell at (5,11,5): surface = (8-3)*0.875/8 = 0.546875.
        float expected = 11 + (8 - 3) * MAX_WATER_HEIGHT / 8.0f;
        boolean found = false;
        for (int q = 0; q + 4 <= water.getVertexCount(); q += 4) {
            if (!isTopFaceOverCell(water, q, 5, 5)) {
                continue;
            }
            found = true;
            float[] pos = water.getVertexPositions();
            for (int v = q; v < q + 4; v++) {
                assertEquals(expected, pos[v * 3 + 1], EPS,
                        "level-3 flowing surface height mismatch");
            }
        }
        assertTrue(found, "flowing cell must emit a top face");
    }

    @Test
    void fallingAndSourceFlagsRideTheVertices() {
        MmsMeshData water = buildMesh().waterMesh();
        float[] falling = water.getAlphaTestFlags();   // water semantics: flags.y = falling
        float[] source = water.getTranslucentFlags();  // water semantics: flags.z = source
        float[] pos = water.getVertexPositions();

        boolean sawFalling = false;
        boolean sawSource = false;
        for (int q = 0; q + 4 <= water.getVertexCount(); q += 4) {
            float cx = quadCenter(pos, q, 0);
            float cy = quadCenter(pos, q, 1);
            float cz = quadCenter(pos, q, 2);
            // Falling column cells (8, 11..13, 8): every vertex flagged falling.
            if (cx > 8 && cx < 9 && cz > 8 && cz < 9 && cy > 11 && cy < 14) {
                for (int v = q; v < q + 4; v++) {
                    assertEquals(1.0f, falling[v], EPS, "falling column vertex must carry falling flag");
                }
                sawFalling = true;
            }
            // Isolated source cell (2,11,2): source flag set, falling flag clear.
            if (cx > 2 && cx < 3 && cz > 2 && cz < 3 && cy > 11 && cy < 12) {
                for (int v = q; v < q + 4; v++) {
                    assertEquals(1.0f, source[v], EPS, "source vertex must carry source flag");
                    assertEquals(0.0f, falling[v], EPS, "source vertex must not carry falling flag");
                }
                sawSource = true;
            }
        }
        assertTrue(sawFalling, "falling column must emit faces");
        assertTrue(sawSource, "source cell must emit faces");
    }

    @Test
    void waterCullingRulesHold() {
        MmsMeshData water = buildMesh().waterMesh();
        float[] pos = water.getVertexPositions();

        for (int q = 0; q + 4 <= water.getVertexCount(); q += 4) {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int v = q; v < q + 4; v++) {
                minX = Math.min(minX, pos[v * 3]);     maxX = Math.max(maxX, pos[v * 3]);
                minY = Math.min(minY, pos[v * 3 + 1]); maxY = Math.max(maxY, pos[v * 3 + 1]);
                minZ = Math.min(minZ, pos[v * 3 + 2]); maxZ = Math.max(maxZ, pos[v * 3 + 2]);
            }
            // No face between the adjacent water pair (2,11,12)-(3,11,12):
            // a vertical quad on the x=3 plane inside that cell pair's bounds.
            boolean onSharedPlane = Math.abs(minX - 3f) < EPS && Math.abs(maxX - 3f) < EPS
                    && minZ >= 12 - EPS && maxZ <= 13 + EPS && minY >= 11 - EPS && maxY <= 12 + EPS;
            assertFalse(onSharedPlane, "water-vs-water face must be culled (found quad on shared plane x=3)");

            // No side face against the stone wall: quad on the x=12 plane
            // inside cell (11,11,2)'s bounds.
            boolean againstWall = Math.abs(minX - 12f) < EPS && Math.abs(maxX - 12f) < EPS
                    && minZ >= 2 - EPS && maxZ <= 3 + EPS && minY >= 11 - EPS && maxY <= 12 + EPS;
            assertFalse(againstWall, "water side face against opaque neighbor must be culled");
        }

        // Top face under air must exist for the walled source cell.
        boolean walledTop = false;
        for (int q = 0; q + 4 <= water.getVertexCount(); q += 4) {
            if (isTopFaceOverCell(water, q, 11, 2)) {
                walledTop = true;
            }
        }
        assertTrue(walledTop, "water cell with air above must emit its top face");
    }

    @Test
    void fallingColumnSideFacesSurviveAtPoolJunction() {
        // Ground water spreads beside the falling column's base: pool cell at
        // (9,11,8), flowing level 2. The falling cell one above the pool,
        // (8,12,8), has AIR to its east with water one below that neighbor —
        // its side face must render (a heuristic cull here punches a
        // see-through hole in the waterfall sheet at the junction). The face's
        // bottom is sealed below the pool's rest surface (11 + 0.65625 - 0.2)
        // so GPU waves can't open a slit.
        chunk.setBlock(9, 11, 8, BlockType.WATER);
        chunk.getWaterLayer().set(9, 11, 8, 2);

        MmsMeshData water = buildMesh().waterMesh();
        assertTrue(hasVerticalQuadOnPlane(water, 0, 9f, 8f, 9f, 11.0f, 13.0f),
                "falling cell above the pool must keep its east side face (x=9 plane)");
    }

    @Test
    void flowingStepDownEmitsOnlyExposedStrip() {
        // Variable-height flowing cell stepping down into surface water below
        // its AIR neighbor: the side face renders, but spans only the cell's
        // actual water extent (bottom on the supporting stone at y=12, top at
        // the flowing surface ~12.19) — never a full-block submerged sheet.
        chunk.setBlock(13, 11, 13, BlockType.STONE);
        chunk.setBlock(13, 12, 13, BlockType.WATER);
        chunk.getWaterLayer().set(13, 12, 13, 6);
        chunk.setBlock(14, 11, 13, BlockType.WATER); // source (no layer entry)

        MmsMeshData water = buildMesh().waterMesh();
        assertTrue(hasVerticalQuadOnPlane(water, 0, 14f, 13f, 14f, 11.9f, 12.4f),
                "flowing step-down must emit its exposed side strip (x=14 plane)");
        assertFalse(hasVerticalQuadOnPlane(water, 0, 14f, 13f, 14f, 11.0f, 11.9f),
                "no submerged sheet below the step (x=14 plane)");
    }

    @Test
    void elevatedRimOverLakeKeepsSideFaces() {
        // Flowing water on a one-block ledge whose AIR neighbor has lake water
        // one below: the rim's side face is fully exposed (it stands a whole
        // block above the lake surface). This is the case a submerged-continuous
        // heuristic mis-culls, turning elevated sheets into floating carpets.
        chunk.setBlock(6, 11, 10, BlockType.STONE);
        chunk.setBlock(6, 12, 10, BlockType.WATER);
        chunk.getWaterLayer().set(6, 12, 10, 4);
        chunk.setBlock(7, 11, 10, BlockType.WATER); // lake source beside the ledge

        MmsMeshData water = buildMesh().waterMesh();
        assertTrue(hasVerticalQuadOnPlane(water, 0, 7f, 10f, 11f, 11.9f, 12.5f),
                "elevated rim over a lake must keep its side face (x=7 plane)");
    }

    @Test
    void removingAllWaterYieldsEmptyWaterMesh() {
        ChunkMeshResult before = buildMesh();
        assertTrue(before.hasWaterMesh());

        // Drain everything (Chunk.setBlock clears water-layer entries).
        chunk.setBlock(2, 11, 2, BlockType.AIR);
        chunk.setBlock(5, 11, 5, BlockType.AIR);
        for (int y = 11; y <= 13; y++) {
            chunk.setBlock(8, y, 8, BlockType.AIR);
        }
        chunk.setBlock(11, 11, 2, BlockType.AIR);
        chunk.setBlock(2, 11, 12, BlockType.AIR);
        chunk.setBlock(3, 11, 12, BlockType.AIR);

        ChunkMeshResult after = buildMesh();
        assertFalse(after.hasWaterMesh(),
                "rebuild after draining must yield an empty water mesh (dry-up contract, CPU side)");
        assertFalse(after.atlasMesh().isEmpty(), "stone floor must still mesh");
    }

    // ------------------------------------------------------------------------------------------

    /**
     * True when the water mesh contains a vertical quad lying on the plane
     * {@code axis == planeCoord} (axis 0 = X, 2 = Z), with the other horizontal
     * coordinate inside [{@code otherMin}, {@code otherMax}] and all four
     * vertices' Y inside [{@code minY}, {@code maxY}].
     */
    private static boolean hasVerticalQuadOnPlane(MmsMeshData mesh, int axis, float planeCoord,
                                                  float otherMin, float otherMax,
                                                  float minY, float maxY) {
        float[] pos = mesh.getVertexPositions();
        int otherAxis = axis == 0 ? 2 : 0;
        for (int q = 0; q + 4 <= mesh.getVertexCount(); q += 4) {
            boolean onPlane = true;
            boolean inBounds = true;
            for (int v = q; v < q + 4; v++) {
                if (Math.abs(pos[v * 3 + axis] - planeCoord) > EPS) {
                    onPlane = false;
                    break;
                }
                float other = pos[v * 3 + otherAxis];
                float y = pos[v * 3 + 1];
                if (other < otherMin - EPS || other > otherMax + EPS
                        || y < minY - EPS || y > maxY + EPS) {
                    inBounds = false;
                    break;
                }
            }
            if (onPlane && inBounds) {
                return true;
            }
        }
        return false;
    }

    /** True when the 4-vertex quad starting at {@code q} is an upward face over cell (cellX, cellZ). */
    private static boolean isTopFaceOverCell(MmsMeshData mesh, int q, int cellX, int cellZ) {
        float[] nrm = mesh.getVertexNormals();
        if (nrm[q * 3 + 1] < 0.5f) {
            return false;
        }
        float cx = quadCenter(mesh.getVertexPositions(), q, 0);
        float cz = quadCenter(mesh.getVertexPositions(), q, 2);
        return cx > cellX && cx < cellX + 1 && cz > cellZ && cz < cellZ + 1;
    }

    private static float quadCenter(float[] pos, int q, int axis) {
        float sum = 0;
        for (int v = q; v < q + 4; v++) {
            sum += pos[v * 3 + axis];
        }
        return sum / 4f;
    }

    private static boolean anyVertexAtHeight(MmsMeshData mesh, float y) {
        float[] pos = mesh.getVertexPositions();
        for (int v = 0; v < mesh.getVertexCount(); v++) {
            if (Math.abs(pos[v * 3 + 1] - y) < EPS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Stubs (same shape as TerrainMeshConsistencyTest's)
    // ------------------------------------------------------------------------------------------

    private static final class StubTextureMapper implements MmsTextureMapper {
        @Override
        public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
            return new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public float[] generateCrossTextureCoordinates(IBlockType blockType) {
            return new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS];
        }

        @Override
        public float[] generateFaceLayers(IBlockType blockType, int face) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public float[] generateCrossLayers(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_CROSS];
        }

        @Override
        public float[] generateAlphaFlags(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public boolean requiresAlphaTesting(IBlockType blockType) {
            return false;
        }
    }

    /** Minimal CcoChunkData view over a Chunk (the production wrapper is private to MmsAPI). */
    private static final class ChunkDataView implements CcoChunkData {
        private final Chunk chunk;

        ChunkDataView(Chunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public IBlockType getBlock(int x, int y, int z) {
            return chunk.getBlock(x, y, z);
        }

        @Override
        public boolean isInBounds(int x, int y, int z) {
            return x >= 0 && x < CHUNK && y >= 0 && y < WORLD_HEIGHT && z >= 0 && z < CHUNK;
        }

        @Override
        public CcoChunkMetadata getMetadata() {
            return null; // never called: getChunkX/getChunkZ are overridden below
        }

        @Override
        public int getChunkX() {
            return chunk.getChunkX();
        }

        @Override
        public int getChunkZ() {
            return chunk.getChunkZ();
        }

        @Override
        public String getBlockState(int x, int y, int z) {
            return chunk.getBlockState(x, y, z);
        }

        @Override
        public int getHighestNonAirY() {
            return chunk.getHighestNonAirY();
        }
    }
}
