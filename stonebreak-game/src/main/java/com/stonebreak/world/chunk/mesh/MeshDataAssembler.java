package com.stonebreak.world.chunk.mesh;

/**
 * Service responsible for assembling final mesh data from temporary arrays.
 * Follows Single Responsibility Principle by handling only mesh data assembly and validation.
 */
public class MeshDataAssembler {
    
    /**
     * Creates the final mesh data result from temporary arrays with safety validation.
     */
    public ChunkMeshOperations.MeshData createMeshDataResult(float[] tempVertices, float[] tempTextureCoords, 
                                                           float[] tempNormals, float[] tempIsWaterFlags, 
                                                           float[] tempIsAlphaTestedFlags, int[] tempIndices,
                                                           int vertexIndex, int textureIndex, int normalIndex, 
                                                           int flagIndex, int indexIndex) {
        if (vertexIndex > 0) {
            // Safety checks to prevent array overruns
            if (vertexIndex > tempVertices.length || textureIndex > tempTextureCoords.length ||
                normalIndex > tempNormals.length || flagIndex > tempIsWaterFlags.length ||
                flagIndex > tempIsAlphaTestedFlags.length || indexIndex > tempIndices.length) {
                System.err.println("CRITICAL: Array indices exceed bounds during mesh data copy");
                System.err.println("Vertex: " + vertexIndex + "/" + tempVertices.length +
                                 ", Texture: " + textureIndex + "/" + tempTextureCoords.length +
                                 ", Normal: " + normalIndex + "/" + tempNormals.length +
                                 ", Flag: " + flagIndex + "/" + tempIsWaterFlags.length +
                                 ", Index: " + indexIndex + "/" + tempIndices.length);
                // Return empty arrays to prevent crash
                return new ChunkMeshOperations.MeshData(new float[0], new float[0], new float[0], new float[0], new float[0], new int[0], 0);
            }
            
            float[] vertexData = new float[vertexIndex];
            System.arraycopy(tempVertices, 0, vertexData, 0, vertexIndex);
            
            float[] textureData = new float[textureIndex];
            System.arraycopy(tempTextureCoords, 0, textureData, 0, textureIndex);
            
            float[] normalData = new float[normalIndex];
            System.arraycopy(tempNormals, 0, normalData, 0, normalIndex);
            
            float[] isWaterData = new float[flagIndex];
            System.arraycopy(tempIsWaterFlags, 0, isWaterData, 0, flagIndex);
            
            float[] isAlphaTestedData = new float[flagIndex];
            System.arraycopy(tempIsAlphaTestedFlags, 0, isAlphaTestedData, 0, flagIndex);
            
            int[] indexData = new int[indexIndex];
            System.arraycopy(tempIndices, 0, indexData, 0, indexIndex);
            
            return new ChunkMeshOperations.MeshData(vertexData, textureData, normalData, isWaterData, isAlphaTestedData, indexData, indexIndex);
        } else {
            // No mesh data generated
            return new ChunkMeshOperations.MeshData(new float[0], new float[0], new float[0], new float[0], new float[0], new int[0], 0);
        }
    }
    
    /**
     * Validates that there is enough space in arrays for additional data.
     * Returns true if there is enough space, false otherwise.
     */
    public boolean validateArraySpace(int vertexIndex, int textureIndex, int normalIndex, int flagIndex, 
                                    int indexIndex, float[] tempVertices, float[] tempTextureCoords,
                                    float[] tempNormals, float[] tempIsWaterFlags, float[] tempIsAlphaTestedFlags,
                                    int[] tempIndices, int verticesNeeded, int texturesNeeded, int normalsNeeded,
                                    int flagsNeeded, int indicesNeeded) {
        if (vertexIndex + verticesNeeded > tempVertices.length || 
            textureIndex + texturesNeeded > tempTextureCoords.length ||
            normalIndex + normalsNeeded > tempNormals.length ||
            flagIndex + flagsNeeded > tempIsWaterFlags.length ||
            flagIndex + flagsNeeded > tempIsAlphaTestedFlags.length ||
            indexIndex + indicesNeeded > tempIndices.length) {
            return false;
        }
        return true;
    }
    
    /**
     * Logs a warning about array overflow and provides debugging information.
     */
    public void logArrayOverflow(String operation, int vertexIndex, int textureIndex, int normalIndex, 
                               int flagIndex, int indexIndex) {
        System.err.println("Warning: Chunk mesh arrays full, skipping " + operation + 
                         ". Vertex: " + vertexIndex + ", Texture: " + textureIndex + 
                         ", Normal: " + normalIndex + ", Flag: " + flagIndex + ", Index: " + indexIndex);
    }
}