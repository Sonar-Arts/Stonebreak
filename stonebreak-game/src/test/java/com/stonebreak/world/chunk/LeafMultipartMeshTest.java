package com.stonebreak.world.chunk;

import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampCache;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.multipart.MultipartRegistry;
import com.stonebreak.blocks.multipart.MultipartShape;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.sbo.MultipartStamps;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rounded leaves through the real {@link MmsCcoAdapter} and stamp emitter, driven only by the
 * leaf SBO's {@code multipart} table: a leaf cell whose neighbours give it a rounded edge leaves
 * the cube stream and is drawn from that combination's stamp; covered leaves and everything else
 * mesh exactly as with connected shapes off. Reads the atlas and stamp meshes together.
 */
class LeafMultipartMeshTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int TOP = 0, BOTTOM = 1, NORTH = 2, SOUTH = 3, EAST = 4, WEST = 5;

    private boolean previousSetting;
    private SBOStampCache cache;
    private MultipartShape shape;
    private TestWorld world;
    private MmsCcoAdapter adapter;
    private Chunk chunk;

    @BeforeEach
    void setUp() throws Exception {
        previousSetting = Settings.getInstance().getConnectedShapes();
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(WorldConfiguration.voxelConfig());
        cache = new SBOStampCache();
        assertTrue(new SBOMeshProcessor().process(BlockType.LEAVES,
            new SBOParser().parse(Path.of("src/main/resources/sbo/blocks/SB_Leaves.sbo")),
            (block, face) -> new float[]{0f, 0f, 1f, 1f}, (block, face) -> face, cache));
        shape = MultipartRegistry.of(BlockType.LEAVES);
        MultipartStamps.register(cache, shape);
        MmsFaceCullingService culling = new MmsFaceCullingService();
        culling.setFaceOcclusionPolicy((block, face) -> MultipartStamps.occludesFace(cache, block, face));

        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        adapter = new MmsCcoAdapter(new UnitUvTextureMapper(), world);
        adapter.setGreedyMeshingEnabled(false); // one record per face keeps the counts exact
        adapter.setSBOStampEmitter(new SBOStampEmitter(cache, culling));
        chunk = new Chunk(0, 0);
        world.setChunk(0, 0, chunk);
    }

    @AfterEach
    void restore() {
        Settings.getInstance().setConnectedShapes(previousSetting);
    }

    /** The stamp for a leaf whose EXPOSED sides (neighbour not a full cube) are {@code exposed}. */
    private BlockStamp stampFor(int exposed) {
        return cache.get(BlockType.LEAVES, shape.key(~exposed & 63));
    }

    private static int vertices(BlockStamp s, int... culledFaces) {
        int n = 0;
        for (int f = 0; f < 6; f++) {
            n += s.interior()[f].vertexCount() + s.faces()[f].vertexCount();
        }
        for (int f : culledFaces) {
            n -= s.faces()[f].vertexCount();
        }
        return n;
    }

    @Test
    void loneLeafBecomesTheFullyRoundedShape() {
        chunk.setBlock(5, 10, 5, BlockType.LEAVES);

        assertEquals(6 * 4, vertexCount(build(false)), "off: a plain cube");
        ChunkMeshResult on = build(true);
        assertEquals(vertices(stampFor(63)), vertexCount(on), "on: the closed rounded shape");
        assertTrue(hasSlantedNormal(on), "the rounded edges carry slanted normals");
    }

    @Test
    void leafOnLogKeepsItsBottomEdgesSharp() {
        chunk.setBlock(5, 10, 5, BlockType.WOOD);
        chunk.setBlock(5, 11, 5, BlockType.LEAVES);

        int off = vertexCount(build(false));
        int exposed = (1 << TOP) | (1 << NORTH) | (1 << SOUTH) | (1 << EAST) | (1 << WEST);
        // The leaf's 5 drawn cube faces become the shape; its flat bottom against the log stays culled.
        assertEquals(off - 5 * 4 + vertices(stampFor(exposed), BOTTOM), vertexCount(build(true)));
        assertFalse(hasVertexBelow(build(true), 11f, 5, 5),
            "nothing of the leaf dips under its own cell");
    }

    @Test
    void coveredLeafStaysACube() {
        // A leaf boxed in on five sides by stone: one exposed face, so no rounded edge.
        chunk.setBlock(5, 10, 5, BlockType.LEAVES);
        chunk.setBlock(5, 9, 5, BlockType.STONE);
        chunk.setBlock(4, 10, 5, BlockType.STONE);
        chunk.setBlock(6, 10, 5, BlockType.STONE);
        chunk.setBlock(5, 10, 4, BlockType.STONE);
        chunk.setBlock(5, 10, 6, BlockType.STONE);

        assertEquals(vertexCount(build(false)), vertexCount(build(true)));
        assertFalse(hasSlantedNormal(build(true)));
    }

    @Test
    void settingOffMeshesExactlyAsBefore() {
        chunk.setBlock(5, 10, 5, BlockType.LEAVES);
        chunk.setBlock(6, 10, 5, BlockType.LEAVES);
        chunk.setBlock(3, 10, 3, BlockType.STONE);

        ChunkMeshResult a = build(false);
        ChunkMeshResult b = build(false);
        assertEquals(vertexCount(a), vertexCount(b));
        assertFalse(hasSlantedNormal(a));
    }

    // ===== Helpers =====

    private ChunkMeshResult build(boolean connected) {
        Settings.getInstance().setConnectedShapes(connected);
        return adapter.generateChunkMesh(new ChunkDataView(chunk),
            chunk.getCcoStateManager(), chunk.getCcoDirtyTracker());
    }

    private static int vertexCount(ChunkMeshResult r) {
        return count(r.atlasMesh()) + count(r.stampMesh());
    }

    private static int count(MmsMeshData mesh) {
        return mesh == null ? 0 : mesh.getVertexCount();
    }

    private static boolean hasSlantedNormal(ChunkMeshResult r) {
        return slanted(r.atlasMesh()) || slanted(r.stampMesh());
    }

    private static boolean slanted(MmsMeshData mesh) {
        if (mesh == null || mesh.getVertexCount() == 0) {
            return false;
        }
        float[] n = mesh.getVertexNormals();
        for (int v = 0; v < mesh.getVertexCount(); v++) {
            int nonZero = 0;
            for (int a = 0; a < 3; a++) {
                if (Math.abs(n[v * 3 + a]) > 1e-3f) nonZero++;
            }
            if (nonZero > 1) {
                return true;
            }
        }
        return false;
    }

    /** Whether any vertex in the column (x, z) lies below {@code y}. */
    private static boolean hasVertexBelow(ChunkMeshResult r, float y, int x, int z) {
        for (MmsMeshData mesh : new MmsMeshData[]{r.atlasMesh(), r.stampMesh()}) {
            if (mesh == null) continue;
            float[] p = mesh.getVertexPositions();
            for (int v = 0; v < mesh.getVertexCount(); v++) {
                boolean inColumn = p[v * 3] > x + 1e-3f && p[v * 3] < x + 1 - 1e-3f
                    && p[v * 3 + 2] > z + 1e-3f && p[v * 3 + 2] < z + 1 - 1e-3f;
                if (inColumn && p[v * 3 + 1] < y - 1e-3f && p[v * 3 + 1] > y - 1 + 1e-3f) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Unit-square UVs in the production corner order (see GreedyMeshEmissionTest). */
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

    /** Minimal CcoChunkData view over a Chunk (same shape as GreedyMeshEmissionTest's). */
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
            return null;
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
