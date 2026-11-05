package com.stonebreak.world.chunk;

import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampCache;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped ore models carry ~2,000 nugget triangles poking out of the cube.
 * In the world they must cost nothing unless exposed: a buried ore meshes
 * exactly like the stone it replaces, and an ore open on one side draws only
 * that side's face and nuggets. Runs the real Coal Ore SBO through the real
 * {@link MmsCcoAdapter} and stamp emitter.
 */
public class OreNuggetCullingTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CX = 8, CY = 20, CZ = 8; // the ore cell

    private BlockStamp oreStamp;
    private SBOStampEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(WorldConfiguration.voxelConfig());
        com.openmason.engine.voxel.lighting.VertexLightSampler.setSmoothLightingEnabled(true);
        SBOStampCache cache = new SBOStampCache();
        ILayerIndexProvider layers = (block, face) -> face;
        SBOMeshProcessor processor = new SBOMeshProcessor();
        assertTrue(processor.process(BlockType.COAL_ORE,
                new SBOParser().parse(Path.of("src/main/resources/sbo/blocks/SB_Coal_Ore.sbo")),
                (block, face) -> new float[]{0f, 0f, 1f, 1f}, layers, cache));
        oreStamp = cache.get(BlockType.COAL_ORE);
        assertTrue(oreStamp.hasProtrusions(), "fixture: the coal ore model must carry nuggets");

        MmsFaceCullingService culling = new MmsFaceCullingService();
        culling.setFaceOcclusionPolicy(cache::occludesFace);
        emitter = new SBOStampEmitter(cache, culling);
    }

    /** A 5×5×5 stone cube around the ore cell, {@code centre} at its middle. */
    private Chunk stoneCube(BlockType centre, boolean openTop) {
        Chunk chunk = new Chunk(0, 0);
        for (int x = CX - 2; x <= CX + 2; x++) {
            for (int y = CY - 2; y <= CY + 2; y++) {
                for (int z = CZ - 2; z <= CZ + 2; z++) {
                    chunk.setBlock(x, y, z, BlockType.STONE);
                }
            }
        }
        chunk.setBlock(CX, CY, CZ, centre);
        if (openTop) {
            chunk.setBlock(CX, CY + 1, CZ, BlockType.AIR);
            chunk.setBlock(CX, CY + 2, CZ, BlockType.AIR);
        }
        return chunk;
    }

    private ChunkMeshResult mesh(Chunk chunk) {
        TestWorld world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        world.setChunk(0, 0, chunk);
        MmsCcoAdapter adapter = new MmsCcoAdapter(new FlatTextureMapper(), world);
        adapter.setSBOStampEmitter(emitter);
        return adapter.generateChunkMesh(new ChunkDataView(chunk),
                chunk.getCcoStateManager(), chunk.getCcoDirtyTracker());
    }

    private static int vertices(MmsMeshData mesh) {
        return mesh == null ? 0 : mesh.getVertexCount();
    }

    @Test
    void aBuriedOreMeshesExactlyLikeTheStoneItReplaces() {
        ChunkMeshResult ore = mesh(stoneCube(BlockType.COAL_ORE, false));
        ChunkMeshResult stone = mesh(stoneCube(BlockType.STONE, false));

        assertEquals(0, vertices(ore.stampMesh()), "a buried ore must emit no face and no nugget");
        assertEquals(vertices(stone.atlasMesh()), vertices(ore.atlasMesh()),
                "the stone around a buried ore must hide its faces exactly as around stone");
    }

    @Test
    void anOreOpenOnOneSideDrawsOnlyThatSidesFaceAndNuggets() {
        ChunkMeshResult ore = mesh(stoneCube(BlockType.COAL_ORE, true));

        int top = SBOFaceConventions.MMS_TOP;
        int expected = oreStamp.faces()[top].vertexCount();
        for (FaceStamp along : oreStamp.protruding()[top]) {
            expected += along.vertexCount();
        }
        int whole = expected;
        for (int side = 0; side < 6; side++) {
            if (side == top) continue;
            whole += oreStamp.faces()[side].vertexCount();
            for (FaceStamp along : oreStamp.protruding()[side]) whole += along.vertexCount();
        }
        assertEquals(expected, vertices(ore.stampMesh()),
                "only the open side's face and nuggets may be emitted");
        assertTrue(expected * 4 < whole, "fixture: one side must be a small part of the model");
    }

    // ------------------------------------------------------------------------------------------
    // Stubs (same shapes as GreedyMeshEmissionTest's)
    // ------------------------------------------------------------------------------------------

    private static final class FlatTextureMapper implements MmsTextureMapper {
        private static final float[] QUAD_UVS = {0, 1, 1, 1, 1, 0, 0, 0};

        @Override public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) { return QUAD_UVS.clone(); }
        @Override public float[] generateCrossTextureCoordinates(IBlockType blockType) {
            return new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS];
        }
        @Override public float[] generateFaceLayers(IBlockType blockType, int face) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }
        @Override public float[] generateCrossLayers(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_CROSS];
        }
        @Override public float[] generateAlphaFlags(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }
        @Override public boolean requiresAlphaTesting(IBlockType blockType) { return false; }
    }

    private static final class ChunkDataView implements CcoChunkData {
        private final Chunk chunk;

        ChunkDataView(Chunk chunk) { this.chunk = chunk; }

        @Override public IBlockType getBlock(int x, int y, int z) { return chunk.getBlock(x, y, z); }
        @Override public boolean isInBounds(int x, int y, int z) {
            return x >= 0 && x < CHUNK && y >= 0 && y < WORLD_HEIGHT && z >= 0 && z < CHUNK;
        }
        @Override public CcoChunkMetadata getMetadata() { return null; }
        @Override public int getChunkX() { return chunk.getChunkX(); }
        @Override public int getChunkZ() { return chunk.getChunkZ(); }
        @Override public String getBlockState(int x, int y, int z) { return chunk.getBlockState(x, y, z); }
        @Override public int getHighestNonAirY() { return chunk.getHighestNonAirY(); }
    }
}
