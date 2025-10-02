package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

/**
 * Mighty Mesh System - Vertex buffer layout definitions.
 *
 * Defines the standard vertex attribute layout used by MMS.
 * This ensures consistency across all mesh generation and rendering operations.
 *
 * Interleaved Layout (40 bytes per vertex):
 * - Position (3 floats = 12 bytes): x, y, z
 * - Texture Coordinates (2 floats = 8 bytes): u, v
 * - Normal (3 floats = 12 bytes): nx, ny, nz
 * - Water Height Flag (1 float = 4 bytes): height encoding
 * - Alpha Test Flag (1 float = 4 bytes): alpha test flag
 *
 * Design Philosophy:
 * - KISS: Simple, well-defined layout
 * - Performance: Interleaved layout optimal for GPU cache
 * - Extensible: Easy to add attributes without breaking compatibility
 *
 * @since MMS 1.0
 */
public final class MmsBufferLayout {

    // Prevent instantiation
    private MmsBufferLayout() {
        throw new AssertionError("MmsBufferLayout is a static utility class");
    }

    // === Vertex Attribute Sizes (in floats) ===

    /** Number of floats per vertex position (x, y, z) */
    public static final int POSITION_SIZE = 3;

    /** Number of floats per texture coordinate (u, v) */
    public static final int TEXTURE_SIZE = 2;

    /** Number of floats per normal vector (nx, ny, nz) */
    public static final int NORMAL_SIZE = 3;

    /** Number of floats per water height flag */
    public static final int WATER_FLAG_SIZE = 1;

    /** Number of floats per alpha test flag */
    public static final int ALPHA_FLAG_SIZE = 1;

    /** Total number of floats per vertex (interleaved) */
    public static final int VERTEX_SIZE = POSITION_SIZE + TEXTURE_SIZE + NORMAL_SIZE +
                                           WATER_FLAG_SIZE + ALPHA_FLAG_SIZE; // = 10

    // === Vertex Attribute Sizes (in bytes) ===

    /** Size of position attribute in bytes */
    public static final int POSITION_SIZE_BYTES = POSITION_SIZE * Float.BYTES; // 12

    /** Size of texture coordinate attribute in bytes */
    public static final int TEXTURE_SIZE_BYTES = TEXTURE_SIZE * Float.BYTES; // 8

    /** Size of normal attribute in bytes */
    public static final int NORMAL_SIZE_BYTES = NORMAL_SIZE * Float.BYTES; // 12

    /** Size of water flag attribute in bytes */
    public static final int WATER_FLAG_SIZE_BYTES = WATER_FLAG_SIZE * Float.BYTES; // 4

    /** Size of alpha flag attribute in bytes */
    public static final int ALPHA_FLAG_SIZE_BYTES = ALPHA_FLAG_SIZE * Float.BYTES; // 4

    /** Total size of one vertex in bytes (stride) */
    public static final int VERTEX_STRIDE_BYTES = VERTEX_SIZE * Float.BYTES; // 40

    // === Vertex Attribute Offsets (in bytes for OpenGL) ===

    /** Offset of position attribute in interleaved buffer */
    public static final long POSITION_OFFSET = 0L;

    /** Offset of texture coordinate attribute in interleaved buffer */
    public static final long TEXTURE_OFFSET = POSITION_SIZE_BYTES;

    /** Offset of normal attribute in interleaved buffer */
    public static final long NORMAL_OFFSET = TEXTURE_OFFSET + TEXTURE_SIZE_BYTES;

    /** Offset of water flag attribute in interleaved buffer */
    public static final long WATER_FLAG_OFFSET = NORMAL_OFFSET + NORMAL_SIZE_BYTES;

    /** Offset of alpha flag attribute in interleaved buffer */
    public static final long ALPHA_FLAG_OFFSET = WATER_FLAG_OFFSET + WATER_FLAG_SIZE_BYTES;

    // === Vertex Attribute Locations (OpenGL shader locations) ===

    /** Shader attribute location for position */
    public static final int POSITION_LOCATION = 0;

    /** Shader attribute location for texture coordinates */
    public static final int TEXTURE_LOCATION = 1;

    /** Shader attribute location for normal */
    public static final int NORMAL_LOCATION = 2;

    /** Shader attribute location for water flag */
    public static final int WATER_FLAG_LOCATION = 3;

    /** Shader attribute location for alpha test flag */
    public static final int ALPHA_FLAG_LOCATION = 4;

    // === Standard Geometry Constants ===

    /** Number of vertices per quad face */
    public static final int VERTICES_PER_QUAD = 4;

    /** Number of indices per quad face (2 triangles) */
    public static final int INDICES_PER_QUAD = 6;

    /** Number of faces per cube */
    public static final int FACES_PER_CUBE = 6;

    /** Number of vertices per cube (6 faces * 4 vertices) */
    public static final int VERTICES_PER_CUBE = FACES_PER_CUBE * VERTICES_PER_QUAD; // 24

    /** Number of indices per cube (6 faces * 6 indices) */
    public static final int INDICES_PER_CUBE = FACES_PER_CUBE * INDICES_PER_QUAD; // 36

    /** Number of vertices per cross (flower blocks: 2 planes * 4 vertices, double-sided via index winding) */
    public static final int VERTICES_PER_CROSS = 8;

    /** Number of indices per cross (2 planes * 2 sides * 2 triangles * 3 indices) */
    public static final int INDICES_PER_CROSS = 24;

    // === Memory Estimation Helpers ===

    /**
     * Calculates the number of floats needed for position data.
     *
     * @param vertexCount Number of vertices
     * @return Number of floats required
     */
    public static int calculatePositionArraySize(int vertexCount) {
        return vertexCount * POSITION_SIZE;
    }

    /**
     * Calculates the number of floats needed for texture coordinate data.
     *
     * @param vertexCount Number of vertices
     * @return Number of floats required
     */
    public static int calculateTextureArraySize(int vertexCount) {
        return vertexCount * TEXTURE_SIZE;
    }

    /**
     * Calculates the number of floats needed for normal data.
     *
     * @param vertexCount Number of vertices
     * @return Number of floats required
     */
    public static int calculateNormalArraySize(int vertexCount) {
        return vertexCount * NORMAL_SIZE;
    }

    /**
     * Calculates the number of floats needed for water flag data.
     *
     * @param vertexCount Number of vertices
     * @return Number of floats required
     */
    public static int calculateWaterFlagArraySize(int vertexCount) {
        return vertexCount * WATER_FLAG_SIZE;
    }

    /**
     * Calculates the number of floats needed for alpha flag data.
     *
     * @param vertexCount Number of vertices
     * @return Number of floats required
     */
    public static int calculateAlphaFlagArraySize(int vertexCount) {
        return vertexCount * ALPHA_FLAG_SIZE;
    }

    /**
     * Calculates the total memory required for interleaved vertex data.
     *
     * @param vertexCount Number of vertices
     * @return Memory in bytes
     */
    public static long calculateInterleavedBufferSize(int vertexCount) {
        return (long) vertexCount * VERTEX_STRIDE_BYTES;
    }

    /**
     * Calculates memory required for index data.
     *
     * @param indexCount Number of indices
     * @return Memory in bytes
     */
    public static long calculateIndexBufferSize(int indexCount) {
        return (long) indexCount * Integer.BYTES;
    }

    /**
     * Calculates total mesh memory including vertex and index data.
     *
     * @param vertexCount Number of vertices
     * @param indexCount Number of indices
     * @return Total memory in bytes
     */
    public static long calculateTotalMeshMemory(int vertexCount, int indexCount) {
        return calculateInterleavedBufferSize(vertexCount) + calculateIndexBufferSize(indexCount);
    }

    // === Validation Helpers ===

    /**
     * Validates that vertex count is reasonable.
     *
     * @param vertexCount Number of vertices
     * @throws IllegalArgumentException if vertex count is invalid
     */
    public static void validateVertexCount(int vertexCount) {
        if (vertexCount < 0) {
            throw new IllegalArgumentException("Vertex count cannot be negative: " + vertexCount);
        }
        if (vertexCount > Integer.MAX_VALUE / VERTEX_SIZE) {
            throw new IllegalArgumentException("Vertex count too large: " + vertexCount);
        }
    }

    /**
     * Validates that index count is reasonable.
     *
     * @param indexCount Number of indices
     * @throws IllegalArgumentException if index count is invalid
     */
    public static void validateIndexCount(int indexCount) {
        if (indexCount < 0) {
            throw new IllegalArgumentException("Index count cannot be negative: " + indexCount);
        }
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("Index count must be multiple of 3 (triangles): " + indexCount);
        }
    }
}
