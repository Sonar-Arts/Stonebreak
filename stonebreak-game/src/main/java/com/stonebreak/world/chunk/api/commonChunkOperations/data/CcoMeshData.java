package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable CPU-side mesh data for CCO chunks.
 * Contains all vertex attributes before GPU upload.
 *
 * Thread-safe through immutability.
 * Designed for zero-copy transfer to buffer upload operations.
 */
public final class CcoMeshData {
    private final float[] vertexData;
    private final float[] textureData;
    private final float[] normalData;
    private final float[] isWaterData;
    private final float[] isAlphaTestedData;
    private final int[] indexData;
    private final int indexCount;

    /**
     * Creates immutable mesh data.
     *
     * @param vertexData Vertex positions (x,y,z per vertex)
     * @param textureData Texture coordinates (u,v per vertex)
     * @param normalData Normal vectors (nx,ny,nz per vertex)
     * @param isWaterData Water flags (1 float per vertex)
     * @param isAlphaTestedData Alpha test flags (1 float per vertex)
     * @param indexData Triangle indices
     * @param indexCount Number of valid indices
     */
    public CcoMeshData(float[] vertexData, float[] textureData, float[] normalData,
                       float[] isWaterData, float[] isAlphaTestedData,
                       int[] indexData, int indexCount) {
        this.vertexData = Objects.requireNonNull(vertexData, "vertexData cannot be null");
        this.textureData = Objects.requireNonNull(textureData, "textureData cannot be null");
        this.normalData = Objects.requireNonNull(normalData, "normalData cannot be null");
        this.isWaterData = Objects.requireNonNull(isWaterData, "isWaterData cannot be null");
        this.isAlphaTestedData = Objects.requireNonNull(isAlphaTestedData, "isAlphaTestedData cannot be null");
        this.indexData = Objects.requireNonNull(indexData, "indexData cannot be null");
        this.indexCount = indexCount;

        if (indexCount < 0 || indexCount > indexData.length) {
            throw new IllegalArgumentException("indexCount out of bounds: " + indexCount);
        }
    }

    /**
     * Creates an empty mesh (no geometry).
     */
    public static CcoMeshData empty() {
        return new CcoMeshData(
                new float[0], new float[0], new float[0],
                new float[0], new float[0], new int[0], 0
        );
    }

    /**
     * Checks if this mesh has no geometry.
     */
    public boolean isEmpty() {
        return indexCount == 0 || vertexData.length == 0;
    }

    /**
     * Validates array sizes are consistent.
     */
    public boolean isValid() {
        if (isEmpty()) {
            return true;
        }

        int vertexCount = getVertexCount();
        if (vertexCount == 0) {
            return false;
        }

        return textureData.length == vertexCount * 2 &&
               normalData.length == vertexCount * 3 &&
               isWaterData.length == vertexCount &&
               isAlphaTestedData.length == vertexCount &&
               indexData.length >= indexCount &&
               indexCount % 3 == 0;
    }

    public float[] getVertexData() {
        return vertexData;
    }

    public float[] getTextureData() {
        return textureData;
    }

    public float[] getNormalData() {
        return normalData;
    }

    public float[] getIsWaterData() {
        return isWaterData;
    }

    public float[] getIsAlphaTestedData() {
        return isAlphaTestedData;
    }

    public int[] getIndexData() {
        return indexData;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public int getVertexCount() {
        return vertexData.length / 3;
    }

    public int getTriangleCount() {
        return indexCount / 3;
    }

    /**
     * Estimates memory usage in bytes.
     */
    public long estimateMemoryUsage() {
        return (long) vertexData.length * 4 +
               (long) textureData.length * 4 +
               (long) normalData.length * 4 +
               (long) isWaterData.length * 4 +
               (long) isAlphaTestedData.length * 4 +
               (long) indexData.length * 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CcoMeshData that = (CcoMeshData) o;
        return indexCount == that.indexCount &&
               Arrays.equals(vertexData, that.vertexData) &&
               Arrays.equals(textureData, that.textureData) &&
               Arrays.equals(normalData, that.normalData) &&
               Arrays.equals(isWaterData, that.isWaterData) &&
               Arrays.equals(isAlphaTestedData, that.isAlphaTestedData) &&
               Arrays.equals(indexData, that.indexData);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(indexCount);
        result = 31 * result + Arrays.hashCode(vertexData);
        result = 31 * result + Arrays.hashCode(textureData);
        result = 31 * result + Arrays.hashCode(normalData);
        result = 31 * result + Arrays.hashCode(isWaterData);
        result = 31 * result + Arrays.hashCode(isAlphaTestedData);
        result = 31 * result + Arrays.hashCode(indexData);
        return result;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "CcoMeshData{empty}";
        }
        return String.format("CcoMeshData{vertices=%d, triangles=%d, memory=%d bytes}",
                getVertexCount(), getTriangleCount(), estimateMemoryUsage());
    }
}
