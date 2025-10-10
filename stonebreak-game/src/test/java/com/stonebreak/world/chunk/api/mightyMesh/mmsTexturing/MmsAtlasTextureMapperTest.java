package com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MmsAtlasTextureMapper - the chunk texture coordinate generation system.
 *
 * Tests texture coordinate generation for:
 * - Cube faces (6 faces)
 * - Cross-section blocks (flowers)
 * - Water flags (animated water surface)
 * - Alpha flags (transparency)
 */
@DisplayName("MmsAtlasTextureMapper Tests")
public class MmsAtlasTextureMapperTest {

    private TextureAtlas mockAtlas;
    private MmsAtlasTextureMapper mapper;

    @BeforeEach
    void setUp() {
        mockAtlas = Mockito.mock(TextureAtlas.class);
        mapper = new MmsAtlasTextureMapper(mockAtlas);
    }

    // === Constructor Tests ===

    @Test
    @DisplayName("Constructor should throw when atlas is null")
    void constructorShouldThrowWhenAtlasIsNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            new MmsAtlasTextureMapper(null);
        });
    }

    @Test
    @DisplayName("Constructor should accept valid atlas")
    void constructorShouldAcceptValidAtlas() {
        assertDoesNotThrow(() -> {
            new MmsAtlasTextureMapper(mockAtlas);
        });
    }

    // === Face Texture Coordinate Tests ===

    @Test
    @DisplayName("Should generate texture coordinates for cube face")
    void shouldGenerateTextureCoordinatesForCubeFace() {
        // Setup mock atlas to return test UVs
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f}; // u1, v1, u2, v2
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Generate texture coordinates for top face
        float[] texCoords = mapper.generateFaceTextureCoordinates(BlockType.STONE, 0);

        // Verify array size (4 vertices * 2 components per vertex)
        assertEquals(MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD,
            texCoords.length, "Texture coordinate array should have 8 floats");

        // Verify quad texture mapping (counter-clockwise winding)
        // Vertex 0: Bottom-left → (u1, v2)
        assertEquals(testUVs[0], texCoords[0], "Vertex 0 U should be u1");
        assertEquals(testUVs[3], texCoords[1], "Vertex 0 V should be v2");

        // Vertex 1: Bottom-right → (u2, v2)
        assertEquals(testUVs[2], texCoords[2], "Vertex 1 U should be u2");
        assertEquals(testUVs[3], texCoords[3], "Vertex 1 V should be v2");

        // Vertex 2: Top-right → (u2, v1)
        assertEquals(testUVs[2], texCoords[4], "Vertex 2 U should be u2");
        assertEquals(testUVs[1], texCoords[5], "Vertex 2 V should be v1");

        // Vertex 3: Top-left → (u1, v1)
        assertEquals(testUVs[0], texCoords[6], "Vertex 3 U should be u1");
        assertEquals(testUVs[1], texCoords[7], "Vertex 3 V should be v1");
    }

    @Test
    @DisplayName("Should validate face index range")
    void shouldValidateFaceIndexRange() {
        // Setup mock atlas to return test UVs for any face
        float[] testUVs = {0.0f, 0.0f, 0.25f, 0.25f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Test valid range (0-5)
        for (int face = 0; face < 6; face++) {
            int finalFace = face;
            assertDoesNotThrow(() -> {
                mapper.generateFaceTextureCoordinates(BlockType.STONE, finalFace);
            });
        }

        // Test invalid negative
        assertThrows(IllegalArgumentException.class, () -> {
            mapper.generateFaceTextureCoordinates(BlockType.STONE, -1);
        });

        // Test invalid too high
        assertThrows(IllegalArgumentException.class, () -> {
            mapper.generateFaceTextureCoordinates(BlockType.STONE, 6);
        });
    }

    @Test
    @DisplayName("Should generate correct UVs for all 6 cube faces")
    void shouldGenerateCorrectUVsForAllSixCubeFaces() {
        float[] testUVs = {0.1f, 0.2f, 0.3f, 0.4f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Test each face
        for (int face = 0; face < 6; face++) {
            float[] texCoords = mapper.generateFaceTextureCoordinates(BlockType.DIRT, face);
            assertNotNull(texCoords, "Face " + face + " should generate texture coordinates");
            assertEquals(8, texCoords.length, "Face " + face + " should have 8 texture coordinates");
        }
    }

    // === Cross Block Tests ===

    @Test
    @DisplayName("Should generate texture coordinates for cross blocks")
    void shouldGenerateTextureCoordinatesForCrossBlocks() {
        float[] testUVs = {0.0f, 0.0f, 0.5f, 1.0f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        // Generate cross texture coordinates (flowers)
        float[] texCoords = mapper.generateCrossTextureCoordinates(BlockType.ROSE);

        // Cross blocks have 4 quads (2 planes * 2 sides) = 16 vertices * 2 = 32 floats
        assertEquals(MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS,
            texCoords.length, "Cross block should have 32 texture coordinates");

        // Verify all quads use same texture mapping
        for (int quad = 0; quad < 4; quad++) {
            int offset = quad * 8; // 8 floats per quad
            assertEquals(testUVs[0], texCoords[offset + 0], "Quad " + quad + " v0 U should be u1");
            assertEquals(testUVs[3], texCoords[offset + 1], "Quad " + quad + " v0 V should be v2");
        }
    }

    @Test
    @DisplayName("Should throw for non-cross blocks when generating cross coordinates")
    void shouldThrowForNonCrossBlocksWhenGeneratingCrossCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> {
            mapper.generateCrossTextureCoordinates(BlockType.STONE);
        });
    }

    @Test
    @DisplayName("Should support both rose and dandelion cross blocks")
    void shouldSupportBothRoseAndDandelionCrossBlocks() {
        float[] testUVs = {0.0f, 0.0f, 0.5f, 1.0f};
        when(mockAtlas.getBlockFaceUVs(any(BlockType.class), any(BlockType.Face.class)))
            .thenReturn(testUVs);

        assertDoesNotThrow(() -> {
            mapper.generateCrossTextureCoordinates(BlockType.ROSE);
        });

        assertDoesNotThrow(() -> {
            mapper.generateCrossTextureCoordinates(BlockType.DANDELION);
        });
    }

    // === Alpha Flag Tests ===

    @Test
    @DisplayName("Should generate alpha flags for transparent blocks")
    void shouldGenerateAlphaFlagsForTransparentBlocks() {
        float[] flags = mapper.generateAlphaFlags(BlockType.LEAVES);

        assertEquals(4, flags.length, "Should have 4 alpha flags for quad");
        for (float flag : flags) {
            assertEquals(1.0f, flag, "Leaves should require alpha testing");
        }
    }

    @Test
    @DisplayName("Should generate zero alpha flags for opaque blocks")
    void shouldGenerateZeroAlphaFlagsForOpaqueBlocks() {
        float[] flags = mapper.generateAlphaFlags(BlockType.STONE);

        assertEquals(4, flags.length, "Should have 4 alpha flags");
        for (float flag : flags) {
            assertEquals(0.0f, flag, "Stone should not require alpha testing");
        }
    }

    @Test
    @DisplayName("Should generate cross alpha flags for cross blocks")
    void shouldGenerateCrossAlphaFlagsForCrossBlocks() {
        float[] flags = mapper.generateCrossAlphaFlags(BlockType.ROSE);

        assertEquals(16, flags.length, "Cross blocks should have 16 alpha flags");
        for (float flag : flags) {
            assertEquals(1.0f, flag, "Flowers should require alpha testing");
        }
    }

    @Test
    @DisplayName("Should identify all leaf types as requiring alpha testing")
    void shouldIdentifyAllLeafTypesAsRequiringAlphaTesting() {
        assertTrue(mapper.requiresAlphaTesting(BlockType.LEAVES));
        assertTrue(mapper.requiresAlphaTesting(BlockType.PINE_LEAVES));
        assertTrue(mapper.requiresAlphaTesting(BlockType.ELM_LEAVES));
    }

    @Test
    @DisplayName("Should identify cross blocks as requiring alpha testing")
    void shouldIdentifyCrossBlocksAsRequiringAlphaTesting() {
        assertTrue(mapper.requiresAlphaTesting(BlockType.ROSE));
        assertTrue(mapper.requiresAlphaTesting(BlockType.DANDELION));
    }

    // === Integration Tests ===

    @Test
    @DisplayName("Should provide access to texture atlas")
    void shouldProvideAccessToTextureAtlas() {
        assertSame(mockAtlas, mapper.getTextureAtlas(),
            "Should return same atlas instance provided in constructor");
    }
}
