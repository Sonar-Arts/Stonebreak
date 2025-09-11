package com.stonebreak.world.chunk.operations;

/**
 * Immutable state container for OpenGL buffer resources belonging to a chunk.
 * Holds VAO and VBO IDs with validation and lifecycle tracking.
 */
public class ChunkBufferState {
    
    private final int vaoId;
    private final int vertexVboId;
    private final int textureVboId;
    private final int normalVboId;
    private final int isWaterVboId;
    private final int isAlphaTestedVboId;
    private final int indexVboId;
    private final boolean isValid;
    
    /**
     * Creates buffer state with the provided OpenGL resource IDs.
     * @param vaoId Vertex Array Object ID
     * @param vertexVboId Vertex buffer ID
     * @param textureVboId Texture coordinate buffer ID
     * @param normalVboId Normal vector buffer ID
     * @param isWaterVboId Water flag buffer ID
     * @param isAlphaTestedVboId Alpha test flag buffer ID
     * @param indexVboId Index buffer ID
     */
    public ChunkBufferState(int vaoId, int vertexVboId, int textureVboId, int normalVboId, 
                           int isWaterVboId, int isAlphaTestedVboId, int indexVboId) {
        this.vaoId = vaoId;
        this.vertexVboId = vertexVboId;
        this.textureVboId = textureVboId;
        this.normalVboId = normalVboId;
        this.isWaterVboId = isWaterVboId;
        this.isAlphaTestedVboId = isAlphaTestedVboId;
        this.indexVboId = indexVboId;
        this.isValid = vaoId > 0 && vertexVboId > 0 && textureVboId > 0 && 
                      normalVboId > 0 && isWaterVboId > 0 && isAlphaTestedVboId > 0 && indexVboId > 0;
    }
    
    /**
     * Creates an empty/invalid buffer state (no OpenGL resources allocated).
     */
    public static ChunkBufferState empty() {
        return new ChunkBufferState(0, 0, 0, 0, 0, 0, 0);
    }
    
    /**
     * Gets the Vertex Array Object ID.
     * @return VAO ID, or 0 if not allocated
     */
    public int getVaoId() {
        return vaoId;
    }
    
    /**
     * Gets the vertex buffer ID.
     * @return Vertex VBO ID, or 0 if not allocated
     */
    public int getVertexVboId() {
        return vertexVboId;
    }
    
    /**
     * Gets the texture coordinate buffer ID.
     * @return Texture VBO ID, or 0 if not allocated
     */
    public int getTextureVboId() {
        return textureVboId;
    }
    
    /**
     * Gets the normal vector buffer ID.
     * @return Normal VBO ID, or 0 if not allocated
     */
    public int getNormalVboId() {
        return normalVboId;
    }
    
    /**
     * Gets the water flag buffer ID.
     * @return Water VBO ID, or 0 if not allocated
     */
    public int getIsWaterVboId() {
        return isWaterVboId;
    }
    
    /**
     * Gets the alpha test flag buffer ID.
     * @return Alpha test VBO ID, or 0 if not allocated
     */
    public int getIsAlphaTestedVboId() {
        return isAlphaTestedVboId;
    }
    
    /**
     * Gets the index buffer ID.
     * @return Index VBO ID, or 0 if not allocated
     */
    public int getIndexVboId() {
        return indexVboId;
    }
    
    /**
     * Checks if this buffer state represents valid allocated OpenGL resources.
     * @return true if all buffer IDs are valid (> 0)
     */
    public boolean isValid() {
        return isValid;
    }
    
    /**
     * Checks if this buffer state is empty (no resources allocated).
     * @return true if no OpenGL resources are allocated
     */
    public boolean isEmpty() {
        return !isValid;
    }
    
    @Override
    public String toString() {
        if (isEmpty()) {
            return "ChunkBufferState{empty}";
        }
        return String.format("ChunkBufferState{VAO=%d, vertex=%d, texture=%d, normal=%d, water=%d, alpha=%d, index=%d}", 
                           vaoId, vertexVboId, textureVboId, normalVboId, isWaterVboId, isAlphaTestedVboId, indexVboId);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ChunkBufferState other = (ChunkBufferState) obj;
        return vaoId == other.vaoId &&
               vertexVboId == other.vertexVboId &&
               textureVboId == other.textureVboId &&
               normalVboId == other.normalVboId &&
               isWaterVboId == other.isWaterVboId &&
               isAlphaTestedVboId == other.isAlphaTestedVboId &&
               indexVboId == other.indexVboId;
    }
    
    @Override
    public int hashCode() {
        int result = vaoId;
        result = 31 * result + vertexVboId;
        result = 31 * result + textureVboId;
        result = 31 * result + normalVboId;
        result = 31 * result + isWaterVboId;
        result = 31 * result + isAlphaTestedVboId;
        result = 31 * result + indexVboId;
        return result;
    }
}