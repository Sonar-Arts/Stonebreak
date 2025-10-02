package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import java.util.Objects;

/**
 * Immutable handle to OpenGL buffer resources for CCO chunks.
 * Contains VAO and VBO IDs with validation.
 *
 * Thread-safe through immutability.
 * Designed for efficient buffer lifecycle management.
 */
public final class CcoBufferHandle {
    private final int vaoId;
    private final int vertexVboId;
    private final int textureVboId;
    private final int normalVboId;
    private final int isWaterVboId;
    private final int isAlphaTestedVboId;
    private final int indexVboId;

    /**
     * Creates a buffer handle with OpenGL resource IDs.
     *
     * @param vaoId Vertex Array Object ID
     * @param vertexVboId Vertex buffer ID
     * @param textureVboId Texture coordinate buffer ID
     * @param normalVboId Normal vector buffer ID
     * @param isWaterVboId Water flag buffer ID
     * @param isAlphaTestedVboId Alpha test flag buffer ID
     * @param indexVboId Index buffer ID
     */
    public CcoBufferHandle(int vaoId, int vertexVboId, int textureVboId, int normalVboId,
                           int isWaterVboId, int isAlphaTestedVboId, int indexVboId) {
        this.vaoId = vaoId;
        this.vertexVboId = vertexVboId;
        this.textureVboId = textureVboId;
        this.normalVboId = normalVboId;
        this.isWaterVboId = isWaterVboId;
        this.isAlphaTestedVboId = isAlphaTestedVboId;
        this.indexVboId = indexVboId;
    }

    /**
     * Creates an empty/invalid handle (no resources allocated).
     */
    public static CcoBufferHandle empty() {
        return new CcoBufferHandle(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Checks if this handle represents valid allocated resources.
     */
    public boolean isValid() {
        return vaoId > 0 && vertexVboId > 0 && textureVboId > 0 &&
               normalVboId > 0 && isWaterVboId > 0 &&
               isAlphaTestedVboId > 0 && indexVboId > 0;
    }

    /**
     * Checks if this handle is empty (no resources).
     */
    public boolean isEmpty() {
        return !isValid();
    }

    public int getVaoId() {
        return vaoId;
    }

    public int getVertexVboId() {
        return vertexVboId;
    }

    public int getTextureVboId() {
        return textureVboId;
    }

    public int getNormalVboId() {
        return normalVboId;
    }

    public int getIsWaterVboId() {
        return isWaterVboId;
    }

    public int getIsAlphaTestedVboId() {
        return isAlphaTestedVboId;
    }

    public int getIndexVboId() {
        return indexVboId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CcoBufferHandle that = (CcoBufferHandle) o;
        return vaoId == that.vaoId &&
               vertexVboId == that.vertexVboId &&
               textureVboId == that.textureVboId &&
               normalVboId == that.normalVboId &&
               isWaterVboId == that.isWaterVboId &&
               isAlphaTestedVboId == that.isAlphaTestedVboId &&
               indexVboId == that.indexVboId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vaoId, vertexVboId, textureVboId, normalVboId,
                isWaterVboId, isAlphaTestedVboId, indexVboId);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "CcoBufferHandle{empty}";
        }
        return String.format("CcoBufferHandle{VAO=%d, VBOs=[%d,%d,%d,%d,%d,%d]}",
                vaoId, vertexVboId, textureVboId, normalVboId,
                isWaterVboId, isAlphaTestedVboId, indexVboId);
    }
}
