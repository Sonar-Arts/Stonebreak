package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MmsMeshBuilder - the fluent mesh building system.
 *
 * Tests mesh construction for:
 * - Vertex and face building
 * - Texture coordinate integration
 * - Builder validation
 * - Array management
 */
@DisplayName("MmsMeshBuilder Tests")
public class MmsMeshBuilderTest {

    private MmsMeshBuilder builder;

    @BeforeEach
    void setUp() {
        builder = MmsMeshBuilder.create();
    }

    // === Constructor Tests ===

    @Test
    @DisplayName("Should create builder with default capacity")
    void shouldCreateBuilderWithDefaultCapacity() {
        MmsMeshBuilder defaultBuilder = MmsMeshBuilder.create();
        assertNotNull(defaultBuilder);
        assertTrue(defaultBuilder.isEmpty());
    }

    @Test
    @DisplayName("Should create builder with custom capacity")
    void shouldCreateBuilderWithCustomCapacity() {
        MmsMeshBuilder customBuilder = MmsMeshBuilder.createWithCapacity(1000);
        assertNotNull(customBuilder);
        assertTrue(customBuilder.isEmpty());
    }

    // === Vertex Building Tests ===

    @Test
    @DisplayName("Should add single vertex with all attributes")
    void shouldAddSingleVertexWithAllAttributes() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0.0f, 0.0f, 0, 1, 0, 0.5f, 1.0f)
            .addVertex(1, 0, 0, 1.0f, 0.0f, 0, 1, 0, 0.5f, 1.0f)
            .addVertex(1, 1, 0, 1.0f, 1.0f, 0, 1, 0, 0.5f, 1.0f)
            .addVertex(0, 1, 0, 0.0f, 1.0f, 0, 1, 0, 0.5f, 1.0f)
            .endFace();

        assertEquals(4, builder.getVertexCount(), "Should have 4 vertices");
        assertEquals(6, builder.getIndexCount(), "Should have 6 indices (2 triangles)");
    }

    @Test
    @DisplayName("Should track vertex count correctly")
    void shouldTrackVertexCountCorrectly() {
        assertEquals(0, builder.getVertexCount(), "Should start with 0 vertices");

        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0);

        assertEquals(2, builder.getVertexCount(), "Should have 2 vertices");
    }

    @Test
    @DisplayName("Should build empty mesh when no vertices added")
    void shouldBuildEmptyMeshWhenNoVerticesAdded() {
        MmsMeshData mesh = builder.build();
        assertTrue(mesh.isEmpty(), "Mesh should be empty");
    }

    // === Face Building Tests ===

    @Test
    @DisplayName("Should build complete quad face")
    void shouldBuildCompleteQuadFace() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0.0f, 0.0f, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 0.25f, 0.0f, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 0.25f, 0.25f, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0.0f, 0.25f, 0, 1, 0, 0, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        assertFalse(mesh.isEmpty(), "Mesh should not be empty");
        assertEquals(4, mesh.getVertexCount(), "Should have 4 vertices");
        assertEquals(6, mesh.getIndexCount(), "Should have 6 indices");
    }

    @Test
    @DisplayName("Should throw when ending face without beginning")
    void shouldThrowWhenEndingFaceWithoutBeginning() {
        assertThrows(IllegalStateException.class, () -> {
            builder.endFace();
        });
    }

    @Test
    @DisplayName("Should throw when face has wrong vertex count")
    void shouldThrowWhenFaceHasWrongVertexCount() {
        assertThrows(IllegalStateException.class, () -> {
            builder.beginFace()
                .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
                .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
                .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
                .endFace(); // Only 3 vertices, need 4
        });
    }

    @Test
    @DisplayName("Should throw when building with incomplete face")
    void shouldThrowWhenBuildingWithIncompleteFace() {
        assertThrows(IllegalStateException.class, () -> {
            builder.beginFace()
                .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
                .build(); // Didn't call endFace()
        });
    }

    @Test
    @DisplayName("Should build multiple faces correctly")
    void shouldBuildMultipleFacesCorrectly() {
        // Build two quads
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace()
            .beginFace()
            .addVertex(0, 0, 1, 0, 0, 0, 0, 1, 0, 0)
            .addVertex(1, 0, 1, 1, 0, 0, 0, 1, 0, 0)
            .addVertex(1, 1, 1, 1, 1, 0, 0, 1, 0, 0)
            .addVertex(0, 1, 1, 0, 1, 0, 0, 1, 0, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        assertEquals(8, mesh.getVertexCount(), "Should have 8 vertices (2 quads)");
        assertEquals(12, mesh.getIndexCount(), "Should have 12 indices (4 triangles)");
    }

    // === Quad Face Helper Tests ===

    @Test
    @DisplayName("Should add quad face using array helper")
    void shouldAddQuadFaceUsingArrayHelper() {
        // Create face data: 4 vertices * 10 floats per vertex
        float[] faceData = new float[40];

        // Vertex 0
        faceData[0] = 0; faceData[1] = 0; faceData[2] = 0; // pos
        faceData[3] = 0.0f; faceData[4] = 0.0f; // tex
        faceData[5] = 0; faceData[6] = 1; faceData[7] = 0; // normal
        faceData[8] = 0; faceData[9] = 0; // flags

        // Vertex 1
        faceData[10] = 1; faceData[11] = 0; faceData[12] = 0;
        faceData[13] = 0.25f; faceData[14] = 0.0f;
        faceData[15] = 0; faceData[16] = 1; faceData[17] = 0;
        faceData[18] = 0; faceData[19] = 0;

        // Vertex 2
        faceData[20] = 1; faceData[21] = 1; faceData[22] = 0;
        faceData[23] = 0.25f; faceData[24] = 0.25f;
        faceData[25] = 0; faceData[26] = 1; faceData[27] = 0;
        faceData[28] = 0; faceData[29] = 0;

        // Vertex 3
        faceData[30] = 0; faceData[31] = 1; faceData[32] = 0;
        faceData[33] = 0.0f; faceData[34] = 0.25f;
        faceData[35] = 0; faceData[36] = 1; faceData[37] = 0;
        faceData[38] = 0; faceData[39] = 0;

        builder.addQuadFace(faceData);

        MmsMeshData mesh = builder.build();
        assertEquals(4, mesh.getVertexCount(), "Should have 4 vertices");
        assertEquals(6, mesh.getIndexCount(), "Should have 6 indices");
    }

    @Test
    @DisplayName("Should throw when quad face array is wrong size")
    void shouldThrowWhenQuadFaceArrayIsWrongSize() {
        float[] wrongSize = new float[30]; // Should be 40

        assertThrows(IllegalArgumentException.class, () -> {
            builder.addQuadFace(wrongSize);
        });
    }

    // === Index Generation Tests ===

    @Test
    @DisplayName("Should generate correct triangle indices for quad")
    void shouldGenerateCorrectTriangleIndicesForQuad() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        int[] indices = mesh.getIndices();

        // First triangle: 0, 1, 2
        assertEquals(0, indices[0]);
        assertEquals(1, indices[1]);
        assertEquals(2, indices[2]);

        // Second triangle: 0, 2, 3
        assertEquals(0, indices[3]);
        assertEquals(2, indices[4]);
        assertEquals(3, indices[5]);
    }

    @Test
    @DisplayName("Should support custom index addition")
    void shouldSupportCustomIndexAddition() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace()
            .addIndex(0)
            .addIndex(3)
            .addIndex(1); // Custom triangle

        assertEquals(9, builder.getIndexCount(), "Should have 6 + 3 indices");
    }

    // === Builder State Tests ===

    @Test
    @DisplayName("Should reset builder state correctly")
    void shouldResetBuilderStateCorrectly() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace();

        builder.reset();

        assertTrue(builder.isEmpty(), "Builder should be empty after reset");
        assertEquals(0, builder.getVertexCount(), "Vertex count should be 0");
        assertEquals(0, builder.getIndexCount(), "Index count should be 0");
    }

    @Test
    @DisplayName("Should reuse builder after build and reset")
    void shouldReuseBuilderAfterBuildAndReset() {
        // First mesh
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace();

        MmsMeshData mesh1 = builder.buildAndReset();
        assertEquals(4, mesh1.getVertexCount());

        // Builder should be reset
        assertTrue(builder.isEmpty());

        // Second mesh
        builder.beginFace()
            .addVertex(2, 2, 2, 0, 0, 1, 0, 0, 0, 0)
            .addVertex(3, 2, 2, 1, 0, 1, 0, 0, 0, 0)
            .addVertex(3, 3, 2, 1, 1, 1, 0, 0, 0, 0)
            .addVertex(2, 3, 2, 0, 1, 1, 0, 0, 0, 0)
            .endFace();

        MmsMeshData mesh2 = builder.buildAndReset();
        assertEquals(4, mesh2.getVertexCount());
    }

    @Test
    @DisplayName("Should check isEmpty correctly")
    void shouldCheckIsEmptyCorrectly() {
        assertTrue(builder.isEmpty(), "New builder should be empty");

        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0);

        assertFalse(builder.isEmpty(), "Builder with vertices should not be empty");
    }

    // === Validation Tests ===

    @Test
    @DisplayName("Should validate mesh on build by default")
    void shouldValidateMeshOnBuildByDefault() {
        // This should succeed with valid data
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace();

        assertDoesNotThrow(() -> builder.build());
    }

    @Test
    @DisplayName("Should allow disabling validation")
    void shouldAllowDisablingValidation() {
        builder.setValidateOnBuild(false);

        // Build mesh (validation disabled)
        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0)
            .endFace();

        assertDoesNotThrow(() -> builder.build());
    }

    // === Texture Coordinate Tests ===

    @Test
    @DisplayName("Should preserve texture coordinates in mesh")
    void shouldPreserveTextureCoordinatesInMesh() {
        float u1 = 0.25f, v1 = 0.5f;
        float u2 = 0.5f, v2 = 0.75f;

        builder.beginFace()
            .addVertex(0, 0, 0, u1, v1, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, u2, v1, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, u2, v2, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, u1, v2, 0, 1, 0, 0, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        float[] texCoords = mesh.getTextureCoordinates();

        // Verify first vertex texture coordinates
        assertEquals(u1, texCoords[0], 0.001f, "First vertex U coordinate");
        assertEquals(v1, texCoords[1], 0.001f, "First vertex V coordinate");

        // Verify third vertex texture coordinates
        assertEquals(u2, texCoords[4], 0.001f, "Third vertex U coordinate");
        assertEquals(v2, texCoords[5], 0.001f, "Third vertex V coordinate");
    }

    @Test
    @DisplayName("Should handle different texture coordinates per vertex")
    void shouldHandleDifferentTextureCoordinatesPerVertex() {
        builder.beginFace()
            .addVertex(0, 0, 0, 0.0f, 0.0f, 0, 1, 0, 0, 0)
            .addVertex(1, 0, 0, 0.25f, 0.0f, 0, 1, 0, 0, 0)
            .addVertex(1, 1, 0, 0.25f, 0.25f, 0, 1, 0, 0, 0)
            .addVertex(0, 1, 0, 0.0f, 0.25f, 0, 1, 0, 0, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        float[] texCoords = mesh.getTextureCoordinates();

        assertEquals(8, texCoords.length, "Should have 8 texture coordinates");

        // All 4 vertices should have different texture coordinates
        assertNotEquals(texCoords[0], texCoords[2]); // v0.u != v1.u
        assertNotEquals(texCoords[1], texCoords[5]); // v0.v != v2.v
    }

    // === Water and Alpha Flag Tests ===

    @Test
    @DisplayName("Should preserve water flags in mesh")
    void shouldPreserveWaterFlagsInMesh() {
        float waterFlag = 0.75f;

        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, waterFlag, 0)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, waterFlag, 0)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, waterFlag, 0)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, waterFlag, 0)
            .endFace();

        MmsMeshData mesh = builder.build();
        float[] waterFlags = mesh.getWaterHeightFlags();

        for (float flag : waterFlags) {
            assertEquals(waterFlag, flag, 0.001f);
        }
    }

    @Test
    @DisplayName("Should preserve alpha flags in mesh")
    void shouldPreserveAlphaFlagsInMesh() {
        float alphaFlag = 1.0f;

        builder.beginFace()
            .addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, alphaFlag)
            .addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, alphaFlag)
            .addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, alphaFlag)
            .addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, alphaFlag)
            .endFace();

        MmsMeshData mesh = builder.build();
        float[] alphaFlags = mesh.getAlphaTestFlags();

        for (float flag : alphaFlags) {
            assertEquals(alphaFlag, flag, 0.001f);
        }
    }
}
