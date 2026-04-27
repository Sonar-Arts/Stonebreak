package com.openmason.engine.voxel.mms.mmsCore;

/**
 * Mighty Mesh System - Vertex buffer layout definitions.
 *
 * Defines the standard vertex attribute layout used by MMS.
 * This ensures consistency across all mesh generation and rendering operations.
 *
 * Interleaved Layout (36 bytes per vertex):
 * - Position (3 floats = 12 bytes): x, y, z
 * - Texture Coordinates (2 floats = 8 bytes): u, v
 * - Normal (3 floats = 12 bytes): nx, ny, nz
 * - Flags (4 unsigned bytes normalized = 4 bytes):
 *     .x water-height (0..1), .y alpha-test (0/1), .z translucent (0/1), .w light (0..1)
 *
 * The four flag fields were previously four separate float attributes (48-byte
 * vertex). Packing them into a single RGBA byte attribute saves 12 bytes per
 * vertex — a meaningful chunk of VRAM and bandwidth for world-scale meshes —
 * at the cost of 1/255 precision loss on the fractional fields (water height,
 * light), which is below perceptual threshold.
 *
 * @since MMS 1.0
 */
public final class MmsBufferLayout {

    // Prevent instantiation
    private MmsBufferLayout() {
        throw new AssertionError("MmsBufferLayout is a static utility class");
    }

    // === Vertex Attribute Sizes (in floats for pos/tex/normal, bytes for flags) ===

    /** Number of floats per vertex position (x, y, z) */
    public static final int POSITION_SIZE = 3;

    /** Number of floats per texture coordinate (u, v) */
    public static final int TEXTURE_SIZE = 2;

    /** Number of floats per normal vector (nx, ny, nz) */
    public static final int NORMAL_SIZE = 3;

    /** Number of bytes in the packed flags attribute (water, alpha, translucent, light). */
    public static final int FLAGS_COMPONENTS = 4;

    // === Vertex Attribute Sizes (in bytes) ===

    public static final int POSITION_SIZE_BYTES = POSITION_SIZE * Float.BYTES; // 12
    public static final int TEXTURE_SIZE_BYTES = TEXTURE_SIZE * Float.BYTES;   // 8
    public static final int NORMAL_SIZE_BYTES = NORMAL_SIZE * Float.BYTES;     // 12
    public static final int FLAGS_SIZE_BYTES = FLAGS_COMPONENTS;               // 4

    /** Total size of one vertex in bytes (stride). */
    public static final int VERTEX_STRIDE_BYTES =
            POSITION_SIZE_BYTES + TEXTURE_SIZE_BYTES + NORMAL_SIZE_BYTES + FLAGS_SIZE_BYTES; // 36

    // === Vertex Attribute Offsets (in bytes) ===

    public static final long POSITION_OFFSET = 0L;
    public static final long TEXTURE_OFFSET = POSITION_SIZE_BYTES;                         // 12
    public static final long NORMAL_OFFSET = TEXTURE_OFFSET + TEXTURE_SIZE_BYTES;          // 20
    public static final long FLAGS_OFFSET = NORMAL_OFFSET + NORMAL_SIZE_BYTES;             // 32

    // === Vertex Attribute Locations (OpenGL shader locations) ===

    public static final int POSITION_LOCATION = 0;
    public static final int TEXTURE_LOCATION = 1;
    public static final int NORMAL_LOCATION = 2;
    /** Packed flags: vec4 of unsigned bytes normalized to [0,1]. Read as aFlags.xyzw in shaders. */
    public static final int FLAGS_LOCATION = 3;

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

    public static int calculatePositionArraySize(int vertexCount) {
        return vertexCount * POSITION_SIZE;
    }

    public static int calculateTextureArraySize(int vertexCount) {
        return vertexCount * TEXTURE_SIZE;
    }

    public static int calculateNormalArraySize(int vertexCount) {
        return vertexCount * NORMAL_SIZE;
    }

    public static long calculateInterleavedBufferSize(int vertexCount) {
        return (long) vertexCount * VERTEX_STRIDE_BYTES;
    }

    public static long calculateIndexBufferSize(int indexCount) {
        return (long) indexCount * Integer.BYTES;
    }

    public static long calculateTotalMeshMemory(int vertexCount, int indexCount) {
        return calculateInterleavedBufferSize(vertexCount) + calculateIndexBufferSize(indexCount);
    }

    // === Validation Helpers ===

    public static void validateVertexCount(int vertexCount) {
        if (vertexCount < 0) {
            throw new IllegalArgumentException("Vertex count cannot be negative: " + vertexCount);
        }
    }

    public static void validateIndexCount(int indexCount) {
        if (indexCount < 0) {
            throw new IllegalArgumentException("Index count cannot be negative: " + indexCount);
        }
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("Index count must be multiple of 3 (triangles): " + indexCount);
        }
    }

    // === Flag Packing ===

    /**
     * Packs the four per-vertex flag values into a single 32-bit little-endian
     * word laid out as byte 0 = water, 1 = alpha, 2 = translucent, 3 = light.
     * Values are clamped to [0,1] then scaled to 0..255. GL reads this word
     * with {@code GL_UNSIGNED_BYTE + normalized=true} so the shader sees it as
     * a vec4 of [0,1] floats.
     */
    public static int packFlags(float water, float alpha, float translucent, float light) {
        int w = toUnsignedByte(water);
        int a = toUnsignedByte(alpha);
        int t = toUnsignedByte(translucent);
        int l = toUnsignedByte(light);
        return w | (a << 8) | (t << 16) | (l << 24);
    }

    /** Clamps a [0,1] float to the 0..255 unsigned-byte range. */
    public static int toUnsignedByte(float f) {
        if (f <= 0f) return 0;
        if (f >= 1f) return 255;
        return (int) (f * 255f + 0.5f);
    }
}
