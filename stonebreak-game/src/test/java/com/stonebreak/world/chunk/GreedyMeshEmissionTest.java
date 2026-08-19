package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end greedy mesh emission through the real {@link MmsCcoAdapter}
 * (native quad stream or classic Java path — both funnel into the same merge +
 * emission pipeline, and the kernel parity guarantee makes them agree).
 *
 * <p>Fixture: a full 16×16 STONE slab at y=10 in an otherwise empty chunk with
 * no loaded neighbors. Every face of the slab has uniform corner light (open
 * sky above, sky factor 0 below, unloaded borders sample nothing), so the slab
 * must collapse to exactly six rectangles: one 16×16 top, one 16×16 bottom,
 * four 16×1 sides.
 *
 * <p>Also pins the REPEAT-tiling texture contract: with the production-shaped
 * unit-square UV rectangle, a merged face's texture coordinates run 0..w/0..h
 * (one repetition per block), in the same orientation a unit face uses.
 */
public class GreedyMeshEmissionTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final float EPS = 1e-4f;

    private TestWorld world;
    private MmsCcoAdapter adapter;
    private Chunk chunk;

    @BeforeEach
    void setUp() {
        // The six-rectangle expectation assumes smooth lighting (border corners
        // average an interior column; flat mode leaves edge quads non-uniform).
        // Pin the global so test ordering can't change the fixture's meaning.
        com.openmason.engine.voxel.lighting.VertexLightSampler.setSmoothLightingEnabled(true);
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        adapter = new MmsCcoAdapter(new UnitUvTextureMapper(), world);

        chunk = new Chunk(0, 0);
        for (int lx = 0; lx < CHUNK; lx++) {
            for (int lz = 0; lz < CHUNK; lz++) {
                chunk.setBlock(lx, 10, lz, BlockType.STONE);
            }
        }
        world.setChunk(0, 0, chunk);
    }

    private MmsMeshData buildAtlas() {
        return adapter.generateChunkMesh(new ChunkDataView(chunk),
                chunk.getCcoStateManager(), chunk.getCcoDirtyTracker()).atlasMesh();
    }

    @Test
    void uniformSlabCollapsesToSixRectangles() {
        MmsMeshData mesh = buildAtlas();
        assertEquals(6 * 4, mesh.getVertexCount(),
                "16x16 slab with uniform lighting must mesh as 6 rectangles (24 vertices)");
    }

    @Test
    void topFaceSpansTheSlabAndTilesItsTexture() {
        MmsMeshData mesh = buildAtlas();
        float[] pos = mesh.getVertexPositions();
        float[] nrm = mesh.getVertexNormals();
        float[] tex = mesh.getTextureCoordinates();

        int topQuads = 0;
        for (int g = 0; g + 4 <= mesh.getVertexCount(); g += 4) {
            if (Math.abs(nrm[g * 3 + 1] - 1f) > EPS || Math.abs(pos[g * 3 + 1] - 11f) > EPS) {
                continue;
            }
            topQuads++;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int v = g; v < g + 4; v++) {
                minX = Math.min(minX, pos[v * 3]);
                maxX = Math.max(maxX, pos[v * 3]);
                minZ = Math.min(minZ, pos[v * 3 + 2]);
                maxZ = Math.max(maxZ, pos[v * 3 + 2]);
                // With the unit-square UV rectangle, a top face's U tracks X
                // and V tracks Z — scaled merging must preserve exactly that
                // (one texture repetition per block).
                assertEquals(pos[v * 3], tex[v * 2], EPS, "top-face U must equal world X");
                assertEquals(pos[v * 3 + 2], tex[v * 2 + 1], EPS, "top-face V must equal world Z");
            }
            assertEquals(0f, minX, EPS);
            assertEquals(16f, maxX, EPS);
            assertEquals(0f, minZ, EPS);
            assertEquals(16f, maxZ, EPS);
        }
        assertEquals(1, topQuads, "the slab top must merge into a single 16x16 rectangle");
    }

    @Test
    void greedyAndPerFaceMeshesCoverIdenticalFaces() {
        MmsMeshData merged = buildAtlas();

        adapter.setGreedyMeshingEnabled(false);
        MmsMeshData perFace = buildAtlas();

        assertTrue(merged.getVertexCount() < perFace.getVertexCount(),
                "greedy mesh must be smaller than the per-face mesh");
        assertEquals(expandFaces(perFace), expandFaces(merged),
                "greedy and per-face meshes must cover the same unit faces");
    }

    /** Expands every axis-aligned integer rectangle into its covered unit faces. */
    private static Set<String> expandFaces(MmsMeshData mesh) {
        Set<String> faces = new HashSet<>();
        float[] pos = mesh.getVertexPositions();
        float[] nrm = mesh.getVertexNormals();
        for (int g = 0; g + 4 <= mesh.getVertexCount(); g += 4) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int v = g; v < g + 4; v++) {
                minX = Math.min(minX, pos[v * 3]);
                maxX = Math.max(maxX, pos[v * 3]);
                minY = Math.min(minY, pos[v * 3 + 1]);
                maxY = Math.max(maxY, pos[v * 3 + 1]);
                minZ = Math.min(minZ, pos[v * 3 + 2]);
                maxZ = Math.max(maxZ, pos[v * 3 + 2]);
            }
            int spanX = Math.max(1, Math.round(maxX - minX));
            int spanY = Math.max(1, Math.round(maxY - minY));
            int spanZ = Math.max(1, Math.round(maxZ - minZ));
            String n = Math.round(nrm[g * 3]) + "," + Math.round(nrm[g * 3 + 1]) + ","
                    + Math.round(nrm[g * 3 + 2]);
            for (int dz = 0; dz < spanZ; dz++) {
                for (int dy = 0; dy < spanY; dy++) {
                    for (int dx = 0; dx < spanX; dx++) {
                        faces.add(n + "@" + (Math.round(minX) + dx) + ","
                                + (Math.round(minY) + dy) + "," + (Math.round(minZ) + dz));
                    }
                }
            }
        }
        return faces;
    }

    // ------------------------------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------------------------------

    /**
     * Production-shaped UV stub: the unit square in the BL, BR, TR, TL corner
     * order {@code MmsArrayTextureMapper} emits — so UV scaling assertions
     * exercise the real orientation math instead of an all-zero placeholder.
     */
    private static final class UnitUvTextureMapper implements MmsTextureMapper {
        private static final float[] QUAD_UVS = {0, 1, 1, 1, 1, 0, 0, 0};

        @Override
        public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
            return QUAD_UVS.clone();
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

    /** Minimal CcoChunkData view over a Chunk (same shape as WaterMeshSplitTest's). */
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
