package com.stonebreak.world.chunk.operations;

/**
 * Immutable data transfer object containing CPU-side mesh data for a chunk.
 * This represents the generated mesh data before it's uploaded to GPU buffers.
 */
public class ChunkMeshData {
    
    private final float[] vertexData;
    private final float[] textureData;
    private final float[] normalData;
    private final float[] isWaterData;
    private final float[] isAlphaTestedData;
    private final int[] indexData;
    private final int indexCount;
    private final boolean isEmpty;
    
    /**
     * Creates mesh data with the provided arrays.
     * @param vertexData Vertex position data (x, y, z per vertex)
     * @param textureData Texture coordinate data (u, v per vertex)
     * @param normalData Normal vector data (nx, ny, nz per vertex)
     * @param isWaterData Water flag data (1 float per vertex)
     * @param isAlphaTestedData Alpha test flag data (1 float per vertex)
     * @param indexData Index data for triangle rendering
     * @param indexCount Number of valid indices to render
     */
    public ChunkMeshData(float[] vertexData, float[] textureData, float[] normalData,
                        float[] isWaterData, float[] isAlphaTestedData, int[] indexData, int indexCount) {
        this.vertexData = vertexData;
        this.textureData = textureData;
        this.normalData = normalData;
        this.isWaterData = isWaterData;
        this.isAlphaTestedData = isAlphaTestedData;
        this.indexData = indexData;
        this.indexCount = indexCount;
        this.isEmpty = indexCount == 0 || vertexData == null || vertexData.length == 0;
    }
    
    /**
     * Creates empty mesh data (no geometry to render).
     */
    public static ChunkMeshData empty() {
        return new ChunkMeshData(new float[0], new float[0], new float[0], 
                                new float[0], new float[0], new int[0], 0);
    }
    
    /**
     * Gets the vertex position data.
     * @return Array of vertex positions (x, y, z per vertex)
     */
    public float[] getVertexData() {
        return vertexData;
    }
    
    /**
     * Gets the texture coordinate data.
     * @return Array of texture coordinates (u, v per vertex)
     */
    public float[] getTextureData() {
        return textureData;
    }
    
    /**
     * Gets the normal vector data.
     * @return Array of normal vectors (nx, ny, nz per vertex)
     */
    public float[] getNormalData() {
        return normalData;
    }
    
    /**
     * Gets the water flag data.
     * @return Array of water flags (1 float per vertex)
     */
    public float[] getIsWaterData() {
        return isWaterData;
    }
    
    /**
     * Gets the alpha test flag data.
     * @return Array of alpha test flags (1 float per vertex)
     */
    public float[] getIsAlphaTestedData() {
        return isAlphaTestedData;
    }
    
    /**
     * Gets the index data for triangle rendering.
     * @return Array of vertex indices
     */
    public int[] getIndexData() {
        return indexData;
    }
    
    /**
     * Gets the number of valid indices to render.
     * @return Number of indices to use for rendering
     */
    public int getIndexCount() {
        return indexCount;
    }
    
    /**
     * Checks if this mesh data is empty (no geometry to render).
     * @return true if the mesh has no renderable geometry
     */
    public boolean isEmpty() {
        return isEmpty;
    }
    
    /**
     * Gets the number of vertices in this mesh.
     * @return Number of vertices (vertex data length / 3)
     */
    public int getVertexCount() {
        return vertexData != null ? vertexData.length / 3 : 0;
    }
    
    /**
     * Gets the number of triangles in this mesh.
     * @return Number of triangles (index count / 3)
     */
    public int getTriangleCount() {
        return indexCount / 3;
    }
    
    /**
     * Validates that all data arrays have consistent sizes.
     * @return true if all arrays have the expected relative sizes
     */
    public boolean isValid() {
        if (isEmpty) {
            return true; // Empty mesh is valid
        }
        
        int expectedVertexCount = getVertexCount();
        if (expectedVertexCount == 0) {
            return false;
        }
        
        // Check that all arrays have consistent sizes relative to vertex count
        return textureData.length == expectedVertexCount * 2 && // 2 components per vertex (u, v)
               normalData.length == expectedVertexCount * 3 && // 3 components per vertex (nx, ny, nz)
               isWaterData.length == expectedVertexCount && // 1 component per vertex
               isAlphaTestedData.length == expectedVertexCount && // 1 component per vertex
               indexData.length >= indexCount && // Index array large enough
               indexCount % 3 == 0; // Indices form complete triangles
    }
    
    /**
     * Creates a string representation with mesh statistics.
     */
    @Override
    public String toString() {
        if (isEmpty) {
            return "ChunkMeshData{empty}";
        }
        return String.format("ChunkMeshData{vertices=%d, triangles=%d, indices=%d}", 
                           getVertexCount(), getTriangleCount(), indexCount);
    }
}