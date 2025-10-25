package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mighty Mesh System - Immutable CPU-side mesh data container.
 *
 * This is the core data structure for all mesh operations in MMS.
 * Follows SOLID principles through immutability and single responsibility.
 *
 * Design Philosophy:
 * - Immutable: Thread-safe by design (KISS principle)
 * - Validated: All data validated on construction (fail-fast)
 * - Efficient: Zero-copy transfer to GPU buffers
 * - Extensible: Easy to add new vertex attributes
 *
 * Performance:
 * - Memory: ~40 bytes overhead + vertex data arrays
 * - Construction: O(1) time, validation O(n) where n = vertex count
 * - Thread-safe: Immutable, safe for concurrent access
 *
 * @since MMS 1.0
 */
public final class MmsMeshData {

    // Vertex attributes (interleaved format: pos[3] + tex[2] + normal[3] + flags[2])
    private final float[] vertexPositions;    // x, y, z per vertex
    private final float[] textureCoordinates;  // u, v per vertex
    private final float[] vertexNormals;       // nx, ny, nz per vertex
    private final float[] waterHeightFlags;    // water height encoding per vertex
    private final float[] alphaTestFlags;      // alpha test flag per vertex

    // Index data
    private final int[] indices;
    private final int indexCount;

    // Metadata
    private final int vertexCount;
    private final int triangleCount;
    private final long memoryUsageBytes;

    // Empty mesh singleton
    private static final MmsMeshData EMPTY = new MmsMeshData(
        new float[0], new float[0], new float[0],
        new float[0], new float[0], new int[0], 0
    );

    /**
     * Creates immutable mesh data with full validation.
     *
     * @param vertexPositions Vertex positions (x,y,z per vertex)
     * @param textureCoordinates Texture coordinates (u,v per vertex)
     * @param vertexNormals Normal vectors (nx,ny,nz per vertex)
     * @param waterHeightFlags Water height encoding (1 float per vertex)
     * @param alphaTestFlags Alpha test flags (1 float per vertex)
     * @param indices Triangle indices
     * @param indexCount Number of valid indices to use
     * @throws NullPointerException if any array is null
     * @throws IllegalArgumentException if arrays are inconsistent or invalid
     */
    public MmsMeshData(float[] vertexPositions, float[] textureCoordinates, float[] vertexNormals,
                       float[] waterHeightFlags, float[] alphaTestFlags, int[] indices, int indexCount) {
        // Null checks
        this.vertexPositions = Objects.requireNonNull(vertexPositions, "vertexPositions cannot be null");
        this.textureCoordinates = Objects.requireNonNull(textureCoordinates, "textureCoordinates cannot be null");
        this.vertexNormals = Objects.requireNonNull(vertexNormals, "vertexNormals cannot be null");
        this.waterHeightFlags = Objects.requireNonNull(waterHeightFlags, "waterHeightFlags cannot be null");
        this.alphaTestFlags = Objects.requireNonNull(alphaTestFlags, "alphaTestFlags cannot be null");
        this.indices = Objects.requireNonNull(indices, "indices cannot be null");
        this.indexCount = indexCount;

        // Validate index count
        if (indexCount < 0 || indexCount > indices.length) {
            throw new IllegalArgumentException(
                String.format("indexCount out of bounds: %d (array length: %d)", indexCount, indices.length)
            );
        }

        // Calculate derived metadata
        this.vertexCount = vertexPositions.length / 3;
        this.triangleCount = indexCount / 3;

        // Validate array sizes are consistent (fail-fast on construction)
        if (!isEmpty()) {
            validateArraySizes();
        }

        // Calculate memory usage
        this.memoryUsageBytes = estimateMemoryUsage();
    }

    /**
     * Returns a reusable empty mesh instance.
     *
     * @return Singleton empty mesh
     */
    public static MmsMeshData empty() {
        return EMPTY;
    }

    /**
     * Checks if this mesh has no geometry.
     *
     * @return true if mesh is empty
     */
    public boolean isEmpty() {
        return indexCount == 0 || vertexCount == 0;
    }

    /**
     * Validates that all array sizes are consistent.
     * Called automatically during construction.
     *
     * @throws IllegalArgumentException if arrays are inconsistent
     */
    private void validateArraySizes() {
        if (textureCoordinates.length != vertexCount * 2) {
            throw new IllegalArgumentException(
                String.format("Texture coordinate array size mismatch: expected %d, got %d",
                    vertexCount * 2, textureCoordinates.length)
            );
        }

        if (vertexNormals.length != vertexCount * 3) {
            throw new IllegalArgumentException(
                String.format("Normal array size mismatch: expected %d, got %d",
                    vertexCount * 3, vertexNormals.length)
            );
        }

        if (waterHeightFlags.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Water flag array size mismatch: expected %d, got %d",
                    vertexCount, waterHeightFlags.length)
            );
        }

        if (alphaTestFlags.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Alpha test flag array size mismatch: expected %d, got %d",
                    vertexCount, alphaTestFlags.length)
            );
        }

        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException(
                String.format("Index count must be multiple of 3 (triangles), got %d", indexCount)
            );
        }
    }

    /**
     * Estimates memory usage in bytes.
     *
     * @return Estimated memory usage
     */
    private long estimateMemoryUsage() {
        return (long) vertexPositions.length * Float.BYTES +
               (long) textureCoordinates.length * Float.BYTES +
               (long) vertexNormals.length * Float.BYTES +
               (long) waterHeightFlags.length * Float.BYTES +
               (long) alphaTestFlags.length * Float.BYTES +
               (long) indices.length * Integer.BYTES +
               // Object overhead (approximate)
               64L;
    }

    // === Getters (defensive copies not needed due to immutability contract) ===

    /**
     * Gets vertex position data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Vertex positions array (x,y,z per vertex)
     */
    public float[] getVertexPositions() {
        return vertexPositions;
    }

    /**
     * Gets texture coordinate data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Texture coordinates array (u,v per vertex)
     */
    public float[] getTextureCoordinates() {
        return textureCoordinates;
    }

    /**
     * Gets vertex normal data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Vertex normals array (nx,ny,nz per vertex)
     */
    public float[] getVertexNormals() {
        return vertexNormals;
    }

    /**
     * Gets water height flag data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Water height flags array (1 float per vertex)
     */
    public float[] getWaterHeightFlags() {
        return waterHeightFlags;
    }

    /**
     * Gets alpha test flag data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Alpha test flags array (1 float per vertex)
     */
    public float[] getAlphaTestFlags() {
        return alphaTestFlags;
    }

    /**
     * Gets index data.
     * WARNING: Do not modify the returned array. This is a direct reference for performance.
     *
     * @return Index array
     */
    public int[] getIndices() {
        return indices;
    }

    /**
     * Gets the number of valid indices to use for rendering.
     *
     * @return Index count
     */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * Gets the number of vertices in this mesh.
     *
     * @return Vertex count
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Gets the number of triangles in this mesh.
     *
     * @return Triangle count
     */
    public int getTriangleCount() {
        return triangleCount;
    }

    /**
     * Gets the estimated memory usage in bytes.
     *
     * @return Memory usage in bytes
     */
    public long getMemoryUsageBytes() {
        return memoryUsageBytes;
    }

    // === Object methods ===

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MmsMeshData that = (MmsMeshData) o;
        return indexCount == that.indexCount &&
               vertexCount == that.vertexCount &&
               Arrays.equals(vertexPositions, that.vertexPositions) &&
               Arrays.equals(textureCoordinates, that.textureCoordinates) &&
               Arrays.equals(vertexNormals, that.vertexNormals) &&
               Arrays.equals(waterHeightFlags, that.waterHeightFlags) &&
               Arrays.equals(alphaTestFlags, that.alphaTestFlags) &&
               Arrays.equals(indices, that.indices);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(indexCount, vertexCount);
        result = 31 * result + Arrays.hashCode(vertexPositions);
        result = 31 * result + Arrays.hashCode(textureCoordinates);
        result = 31 * result + Arrays.hashCode(vertexNormals);
        result = 31 * result + Arrays.hashCode(waterHeightFlags);
        result = 31 * result + Arrays.hashCode(alphaTestFlags);
        result = 31 * result + Arrays.hashCode(indices);
        return result;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "MmsMeshData{empty}";
        }
        return String.format("MmsMeshData{vertices=%d, triangles=%d, memory=%d bytes}",
            vertexCount, triangleCount, memoryUsageBytes);
    }
}
