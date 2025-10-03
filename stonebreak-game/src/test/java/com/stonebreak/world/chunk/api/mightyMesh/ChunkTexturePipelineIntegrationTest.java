package com.stonebreak.world.chunk.api.mightyMesh;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshBuilder;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshData;
import com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing.MmsAtlasTextureMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the complete chunk texture pipeline.
 *
 * Tests the integration of:
 * - MmsAtlasTextureMapper (texture coordinate generation)
 * - MmsMeshBuilder (mesh construction)
 * - Complete block face rendering workflow
 */
@DisplayName("Chunk Texture Pipeline Integration Tests")
public class ChunkTexturePipelineIntegrationTest {

    private TextureAtlas mockAtlas;
    private MmsAtlasTextureMapper textureMapper;
    private MmsMeshBuilder meshBuilder;

    @BeforeEach
    void setUp() {
        mockAtlas = Mockito.mock(TextureAtlas.class);
        textureMapper = new MmsAtlasTextureMapper(mockAtlas);
        meshBuilder = MmsMeshBuilder.create();
    }

    // === Complete Block Face Tests ===

    @Test
    @DisplayName("Should build complete textured cube block")
    void shouldBuildCompleteTexturedCubeBlock() {
        // Setup texture atlas
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Generate texture coordinates for a cube (6 faces)
        for (int face = 0; face < 6; face++) {
            float[] texCoords = textureMapper.generateFaceTextureCoordinates(BlockType.STONE, face);
            float[] alphaFlags = textureMapper.generateAlphaFlags(BlockType.STONE);

            // Build face with texture coordinates
            meshBuilder.beginFace();

            // Add 4 vertices with texture coordinates and alpha flags
            for (int v = 0; v < 4; v++) {
                int texIdx = v * 2;
                meshBuilder.addVertex(
                    v, 0, 0,                    // Position
                    texCoords[texIdx],          // U
                    texCoords[texIdx + 1],      // V
                    0, 1, 0,                    // Normal
                    0.0f,                       // Water flag
                    alphaFlags[v]               // Alpha flag
                );
            }

            meshBuilder.endFace();
        }

        // Build final mesh
        MmsMeshData mesh = meshBuilder.build();

        // Verify mesh structure
        assertEquals(24, mesh.getVertexCount(), "Cube should have 24 vertices (6 faces * 4 vertices)");
        assertEquals(36, mesh.getIndexCount(), "Cube should have 36 indices (6 faces * 6 indices)");

        // Verify texture coordinates are preserved
        float[] meshTexCoords = mesh.getTextureCoordinates();
        assertNotNull(meshTexCoords);
        assertEquals(48, meshTexCoords.length, "Should have 48 texture coordinates (24 vertices * 2)");

        // Verify alpha flags are preserved
        float[] meshAlphaFlags = mesh.getAlphaTestFlags();
        assertNotNull(meshAlphaFlags);
        assertEquals(24, meshAlphaFlags.length, "Should have 24 alpha flags");
        for (float flag : meshAlphaFlags) {
            assertEquals(0.0f, flag, "Stone should not require alpha testing");
        }
    }

    @Test
    @DisplayName("Should build textured cross block (flower)")
    void shouldBuildTexturedCrossBlock() {
        // Setup texture atlas for cross block
        float[] testUVs = {0.0f, 0.0f, 0.5f, 1.0f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Generate cross texture coordinates
        float[] texCoords = textureMapper.generateCrossTextureCoordinates(BlockType.ROSE);
        float[] alphaFlags = textureMapper.generateCrossAlphaFlags(BlockType.ROSE);

        // Build cross mesh (4 quads)
        for (int quad = 0; quad < 4; quad++) {
            meshBuilder.beginFace();

            for (int v = 0; v < 4; v++) {
                int idx = (quad * 4 + v) * 2;
                meshBuilder.addVertex(
                    v, 0, 0,                // Position
                    texCoords[idx],         // U
                    texCoords[idx + 1],     // V
                    0, 1, 0,                // Normal
                    0.0f,                   // Water flag
                    alphaFlags[quad * 4 + v] // Alpha flag
                );
            }

            meshBuilder.endFace();
        }

        // Build final mesh
        MmsMeshData mesh = meshBuilder.build();

        // Verify cross mesh structure
        assertEquals(16, mesh.getVertexCount(), "Cross should have 16 vertices (4 quads)");
        assertEquals(24, mesh.getIndexCount(), "Cross should have 24 indices (4 quads * 6)");

        // Verify alpha flags for transparency
        float[] meshAlphaFlags = mesh.getAlphaTestFlags();
        for (float flag : meshAlphaFlags) {
            assertEquals(1.0f, flag, "Flowers should require alpha testing");
        }
    }

    @Test
    @DisplayName("Should build textured water block")
    void shouldBuildTexturedWaterBlock() {
        // Setup texture atlas
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Build top face (water is treated like any other block)
        float[] texCoords = textureMapper.generateFaceTextureCoordinates(BlockType.WATER, 0);
        float[] alphaFlags = textureMapper.generateAlphaFlags(BlockType.WATER);

        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            int texIdx = v * 2;
            meshBuilder.addVertex(
                v, 0, 0,
                texCoords[texIdx],
                texCoords[texIdx + 1],
                0, 1, 0,
                0.0f,              // No water flags
                alphaFlags[v]
            );
        }
        meshBuilder.endFace();

        MmsMeshData mesh = meshBuilder.build();

        // Verify mesh was built correctly
        assertEquals(4, mesh.getVertexCount());
        assertEquals(6, mesh.getIndexCount());
    }

    @Test
    @DisplayName("Should build textured leaf block with alpha testing")
    void shouldBuildTexturedLeafBlockWithAlphaTesting() {
        // Setup texture atlas
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Build single leaf face
        float[] texCoords = textureMapper.generateFaceTextureCoordinates(BlockType.LEAVES, 0);
        float[] alphaFlags = textureMapper.generateAlphaFlags(BlockType.LEAVES);

        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            int texIdx = v * 2;
            meshBuilder.addVertex(
                v, 0, 0,
                texCoords[texIdx],
                texCoords[texIdx + 1],
                0, 1, 0,
                0.0f,
                alphaFlags[v]
            );
        }
        meshBuilder.endFace();

        MmsMeshData mesh = meshBuilder.build();

        // Verify alpha testing flags
        float[] meshAlphaFlags = mesh.getAlphaTestFlags();
        for (float flag : meshAlphaFlags) {
            assertEquals(1.0f, flag, "Leaves should require alpha testing");
        }
    }

    // === Multi-Block Scene Tests ===

    @Test
    @DisplayName("Should build scene with multiple block types")
    void shouldBuildSceneWithMultipleBlockTypes() {
        float[] stoneUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        float[] dirtUVs = {0.25f, 0.0f, 0.5f, 0.25f};

        when(mockAtlas.getBlockFaceUVs(eq(BlockType.STONE), any(BlockType.Face.class)))
            .thenReturn(stoneUVs);
        when(mockAtlas.getBlockFaceUVs(eq(BlockType.DIRT), any(BlockType.Face.class)))
            .thenReturn(dirtUVs);

        // Build stone block face
        float[] stoneTexCoords = textureMapper.generateFaceTextureCoordinates(BlockType.STONE, 0);
        float[] stoneAlpha = textureMapper.generateAlphaFlags(BlockType.STONE);

        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            meshBuilder.addVertex(
                v, 0, 0,
                stoneTexCoords[v * 2], stoneTexCoords[v * 2 + 1],
                0, 1, 0, 0.0f, stoneAlpha[v]
            );
        }
        meshBuilder.endFace();

        // Build dirt block face
        float[] dirtTexCoords = textureMapper.generateFaceTextureCoordinates(BlockType.DIRT, 0);
        float[] dirtAlpha = textureMapper.generateAlphaFlags(BlockType.DIRT);

        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            meshBuilder.addVertex(
                v + 1, 0, 0,
                dirtTexCoords[v * 2], dirtTexCoords[v * 2 + 1],
                0, 1, 0, 0.0f, dirtAlpha[v]
            );
        }
        meshBuilder.endFace();

        MmsMeshData mesh = meshBuilder.build();

        assertEquals(8, mesh.getVertexCount(), "Should have 8 vertices (2 blocks)");
        assertEquals(12, mesh.getIndexCount(), "Should have 12 indices");

        // Verify different texture coordinates for different blocks
        float[] texCoords = mesh.getTextureCoordinates();
        assertNotEquals(texCoords[0], texCoords[8], "Different blocks should have different UVs");
    }

    // === Builder Reuse Tests ===

    @Test
    @DisplayName("Should reuse builder for multiple meshes")
    void shouldReuseBuilderForMultipleMeshes() {
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Build first mesh
        float[] texCoords1 = textureMapper.generateFaceTextureCoordinates(BlockType.STONE, 0);
        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            meshBuilder.addVertex(v, 0, 0, texCoords1[v * 2], texCoords1[v * 2 + 1],
                0, 1, 0, 0, 0);
        }
        meshBuilder.endFace();

        MmsMeshData mesh1 = meshBuilder.buildAndReset();
        assertEquals(4, mesh1.getVertexCount());

        // Build second mesh (builder was reset)
        float[] texCoords2 = textureMapper.generateFaceTextureCoordinates(BlockType.DIRT, 0);
        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            meshBuilder.addVertex(v, 0, 0, texCoords2[v * 2], texCoords2[v * 2 + 1],
                0, 1, 0, 0, 0);
        }
        meshBuilder.endFace();

        MmsMeshData mesh2 = meshBuilder.buildAndReset();
        assertEquals(4, mesh2.getVertexCount());

        // Both meshes should be independent
        assertNotSame(mesh1, mesh2);
    }

    // === Texture Atlas Integration Tests ===

    @Test
    @DisplayName("Should handle all block types correctly")
    void shouldHandleAllBlockTypesCorrectly() {
        float[] defaultUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(defaultUVs);

        // Test opaque blocks
        BlockType[] opaqueBlocks = {BlockType.STONE, BlockType.DIRT, BlockType.GRASS};
        for (BlockType blockType : opaqueBlocks) {
            assertFalse(textureMapper.requiresAlphaTesting(blockType),
                blockType + " should not require alpha testing");
        }

        // Test transparent blocks
        BlockType[] transparentBlocks = {
            BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES,
            BlockType.ROSE, BlockType.DANDELION
        };
        for (BlockType blockType : transparentBlocks) {
            assertTrue(textureMapper.requiresAlphaTesting(blockType),
                blockType + " should require alpha testing");
        }
    }

    @Test
    @DisplayName("Should generate consistent texture coordinates across pipeline")
    void shouldGenerateConsistentTextureCoordinatesAcrossPipeline() {
        float[] expectedUVs = {0.1f, 0.2f, 0.3f, 0.4f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(expectedUVs);

        // Generate texture coordinates
        float[] texCoords = textureMapper.generateFaceTextureCoordinates(BlockType.STONE, 0);

        // Build mesh with these coordinates
        meshBuilder.beginFace();
        for (int v = 0; v < 4; v++) {
            meshBuilder.addVertex(
                v, 0, 0,
                texCoords[v * 2], texCoords[v * 2 + 1],
                0, 1, 0, 0, 0
            );
        }
        meshBuilder.endFace();

        MmsMeshData mesh = meshBuilder.build();

        // Verify coordinates are preserved through the pipeline
        float[] meshTexCoords = mesh.getTextureCoordinates();

        // Check first vertex
        assertEquals(expectedUVs[0], meshTexCoords[0], 0.001f, "U1 coordinate preserved");
        assertEquals(expectedUVs[3], meshTexCoords[1], 0.001f, "V2 coordinate preserved (bottom-left)");

        // Check third vertex
        assertEquals(expectedUVs[2], meshTexCoords[4], 0.001f, "U2 coordinate preserved");
        assertEquals(expectedUVs[1], meshTexCoords[5], 0.001f, "V1 coordinate preserved (top-right)");
    }

    // === Error Handling Tests ===

    @Test
    @DisplayName("Should handle invalid texture coordinates gracefully")
    void shouldHandleInvalidTextureCoordinatesGracefully() {
        // Test with null UVs (should throw IllegalStateException from mapper)
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(null);

        assertThrows(IllegalStateException.class, () -> {
            textureMapper.generateFaceTextureCoordinates(BlockType.STONE, 0);
        });
    }

    @Test
    @DisplayName("Should validate vertex data before building mesh")
    void shouldValidateVertexDataBeforeBuildingMesh() {
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Try to build with incomplete face
        meshBuilder.beginFace();
        meshBuilder.addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        meshBuilder.addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0);
        // Missing 2 vertices

        assertThrows(IllegalStateException.class, () -> {
            meshBuilder.endFace();
        });
    }
}
