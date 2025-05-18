package com.stonebreak;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a chunk of the world, storing block data and mesh information.
 */
public class Chunk {
    
    private final int x;
    private final int z;
    
    // Block data storage
    private final BlockType[][][] blocks;
    
    // Rendering data
    private int vaoId;
    private int vertexVboId;
    private int textureVboId;
    private int normalVboId;
    private int isWaterVboId; // New VBO for isWater flag
    private int indexVboId;
    private int vertexCount;
    private boolean meshGenerated;
    private volatile boolean dataReadyForGL = false; // Added flag
    private volatile boolean meshDataGenerationScheduledOrInProgress = false; // New flag
    
    /**
     * Creates a new chunk at the specified position.
     */
    public Chunk(int x, int z) {
        this.x = x;
        this.z = z;
        this.blocks = new BlockType[World.CHUNK_SIZE][World.WORLD_HEIGHT][World.CHUNK_SIZE];
        this.meshGenerated = false;
        
        // Initialize all blocks to air
        for (int i = 0; i < World.CHUNK_SIZE; i++) {
            for (int j = 0; j < World.WORLD_HEIGHT; j++) {
                for (int k = 0; k < World.CHUNK_SIZE; k++) {
                    blocks[i][j][k] = BlockType.AIR;
                }
            }
        }
    }
    
    /**
     * Gets the block type at the specified local position.
     */
    public BlockType getBlock(int x, int y, int z) {
        if (x < 0 || x >= World.CHUNK_SIZE || y < 0 || y >= World.WORLD_HEIGHT || z < 0 || z >= World.CHUNK_SIZE) {
            return BlockType.AIR;
        }
        
        return blocks[x][y][z];
    }
    
    /**
     * Sets the block type at the specified local position.
     */
    public void setBlock(int x, int y, int z, BlockType blockType) {
        if (x < 0 || x >= World.CHUNK_SIZE || y < 0 || y >= World.WORLD_HEIGHT || z < 0 || z >= World.CHUNK_SIZE) {
            return;
        }
        
        blocks[x][y][z] = blockType;
    }
    
    /**
     * Builds the mesh data for this chunk. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        // meshDataGenerationScheduledOrInProgress is true, set by World.
        try {
            generateMeshData(world);
            this.dataReadyForGL = true; // Mark data as ready for GL upload ONLY on success
        } catch (Exception e) {
            System.err.println("CRITICAL: Exception during generateMeshData for chunk (" + x + ", " + z + "): " + e.getMessage());
            e.printStackTrace(); // Print stack trace for better debugging
            this.dataReadyForGL = false; // Ensure it's false on error
            // meshDataGenerationScheduledOrInProgress is reset by the worker task's finally block in World.java.
        }
    }

    /**
     * Applies the prepared mesh data to OpenGL. This must be called on the main GL thread.
     */
    public void applyPreparedDataToGL() {
        if (!dataReadyForGL) {
            return; // Data not ready yet or already processed
        }

        // Case 1: Chunk has become empty or has no renderable data
        if (vertexData == null || vertexData.length == 0 || indexData == null || indexData.length == 0 || vertexCount == 0) {
            if (this.meshGenerated) { // If there was an old mesh
                cleanupMesh(); // Deletes current this.vaoId, this.vertexVboId etc.
                this.meshGenerated = false;
                // Ensure IDs are zeroed out after cleanup for clarity
                this.vaoId = 0; this.vertexVboId = 0; this.textureVboId = 0; this.normalVboId = 0; this.isWaterVboId = 0; this.indexVboId = 0;
            }
            this.dataReadyForGL = false; // Data processed
            return;
        }

        // Case 2: We have new mesh data to apply.
        // Store current (old) GL resource IDs and state.
        int tempOldVaoId = this.vaoId;
        int tempOldVertexVboId = this.vertexVboId;
        int tempOldTextureVboId = this.textureVboId;
        int tempOldNormalVboId = this.normalVboId;
        int tempOldIsWaterVboId = this.isWaterVboId; // Store old isWaterVboId
        int tempOldIndexVboId = this.indexVboId;
        boolean oldMeshWasActuallyGenerated = this.meshGenerated;

        try {
            // createMesh() will generate new VAO/VBOs and assign their IDs to this.vaoId, this.vertexVboId, etc.
            // It uses this.vertexData, this.textureData, etc. which are already prepared.
            createMesh();            // If createMesh succeeded, this.vaoId etc. now hold the NEW mesh IDs.
            this.meshGenerated = true; // New mesh is now active and ready for rendering.

            // If an old mesh existed, delete its GL resources now.
            if (oldMeshWasActuallyGenerated) {
                // Only delete if the old VAO ID is valid and different from the new one
                // (should always be different if glGenVertexArrays works correctly).
                if (tempOldVaoId != 0 && tempOldVaoId != this.vaoId) {
                     GL30.glDeleteVertexArrays(tempOldVaoId);
                     GL15.glDeleteBuffers(tempOldVertexVboId);
                     GL15.glDeleteBuffers(tempOldTextureVboId);
                     GL15.glDeleteBuffers(tempOldNormalVboId);
                     GL15.glDeleteBuffers(tempOldIsWaterVboId); // Delete old isWaterVboId
                     GL15.glDeleteBuffers(tempOldIndexVboId);
                } else if (tempOldVaoId != 0 && tempOldVaoId == this.vaoId) {
                    // This is an unexpected state, log a warning.
                    System.err.println("Warning: Old VAO ID " + tempOldVaoId + " is same as new VAO ID " + this.vaoId +
                                       " for chunk (" + x + ", " + z + ") after mesh creation. Old mesh not explicitly deleted to protect new mesh.");
                }
            }
        } catch (Exception e) {
            System.err.println("CRITICAL: Error during createMesh for chunk (" + x + ", " + z + "): " + e.getMessage());
            e.printStackTrace();
            
            // createMesh failed. this.vaoId etc. might now hold IDs of partially created, invalid GL objects.
            // These new, failed GL objects need to be cleaned up. cleanupMesh() uses this.vaoId etc.
            // It's important that createMesh() sets this.vaoId etc. to 0 or valid new IDs before throwing.
            // If createMesh sets IDs before potential failure points, cleanupMesh() will target the new, bad IDs.
            if (this.vaoId != tempOldVaoId || this.vertexVboId != tempOldVertexVboId || this.isWaterVboId != tempOldIsWaterVboId ) { // Check if createMesh changed any ID before failing
                 cleanupMesh(); // This will attempt to delete the new, partially created GL objects.
            }

            // Attempt to restore the old valid mesh's IDs and state if an old mesh existed.
            if (oldMeshWasActuallyGenerated) {
                this.vaoId = tempOldVaoId;
                this.vertexVboId = tempOldVertexVboId;
                this.textureVboId = tempOldTextureVboId;
                this.normalVboId = tempOldNormalVboId;
                this.isWaterVboId = tempOldIsWaterVboId; // Restore old isWaterVboId
                this.indexVboId = tempOldIndexVboId;
                // vertexCount should still correspond to the old mesh if data wasn't changed,
                // but generateMeshData would have updated vertexCount for the new data.
                // This part is tricky; for now, we assume vertexCount from generateMeshData is for the new data.
                // If we revert to old mesh, ideally we'd revert vertexCount too if it changed.
                // However, the primary goal is to keep rendering *something*.
                this.meshGenerated = true; // Old mesh is still considered valid and active.
            } else {
                // No old mesh, and new one failed. Chunk has no valid mesh.
                this.meshGenerated = false;
                this.vaoId = 0; this.vertexVboId = 0; this.textureVboId = 0; this.normalVboId = 0; this.isWaterVboId = 0; this.indexVboId = 0;
            }
        } finally {
            this.dataReadyForGL = false; // Data has been processed (or attempt failed)
        }
    }
      /**
     * Generates the mesh data for the chunk.
     */
    private void generateMeshData(World world) {
        List<Float> vertices = new ArrayList<>();
        List<Float> textureCoords = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> isWaterFlags = new ArrayList<>(); // New list for isWater flags
        List<Integer> indices = new ArrayList<>();
        
        int index = 0;
        
        // Iterate through all blocks in the chunk
        for (int x = 0; x < World.CHUNK_SIZE; x++) {
            for (int y = 0; y < World.WORLD_HEIGHT; y++) {
                for (int z = 0; z < World.CHUNK_SIZE; z++) {
                    BlockType blockType = blocks[x][y][z];
                    
                    // Skip air blocks
                    if (blockType == BlockType.AIR) {
                        continue;
                    }                    
                    // Check each face of the block
                    for (int face = 0; face < 6; face++) {
                        // Check if the adjacent block is solid
                        BlockType adjacentBlock = getAdjacentBlock(x, y, z, face, world);
                        
                        // Determine if the face should be rendered
                        boolean renderFace;
                        // A face of blockType should be rendered if:
                        // 1. The adjacentBlock is transparent, AND (blockType is not WATER OR adjacentBlock is not WATER)
                        //    This handles solid vs transparent, transparent vs different transparent, and water vs non-water transparent.
                        // 2. OR, if blockType itself is transparent AND the adjacentBlock is solid.
                        //    This handles transparent (like leaves/water) vs solid.
                        renderFace = (adjacentBlock.isTransparent() && (blockType != BlockType.WATER || adjacentBlock != BlockType.WATER)) ||
                                     (blockType.isTransparent() && !adjacentBlock.isTransparent());

                        if (renderFace) {
                            // Add face vertices, texture coordinates, normals, isWater flags, and indices
                            index = addFace(x, y, z, face, blockType, vertices, textureCoords, normals, isWaterFlags, indices, index);
                        }
                    }
                }
            }
        }
        
        // Convert lists to arrays
        vertexData = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexData[i] = vertices.get(i);
        }
        
        textureData = new float[textureCoords.size()];
        for (int i = 0; i < textureCoords.size(); i++) {
            textureData[i] = textureCoords.get(i);
        }
        
        normalData = new float[normals.size()];
        for (int i = 0; i < normals.size(); i++) {
            normalData[i] = normals.get(i);
        }
        
        isWaterData = new float[isWaterFlags.size()]; // Convert isWaterFlags list to array
        for (int i = 0; i < isWaterFlags.size(); i++) {
            isWaterData[i] = isWaterFlags.get(i);
        }
        
        indexData = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            indexData[i] = indices.get(i);
        }
        
        vertexCount = indexData.length;
    }    /**
     * Gets the block adjacent to the specified position in the given direction.
     */
    private BlockType getAdjacentBlock(int x, int y, int z, int face, World world) {
        // Determine adjacent block coordinates based on the face
        // 0: Top, 1: Bottom, 2: Front, 3: Back, 4: Right, 5: Left
        try {
            switch (face) {
                case 0: // Top
                    return y + 1 < World.WORLD_HEIGHT ? blocks[x][y + 1][z] : BlockType.AIR;
                case 1: // Bottom
                    return y - 1 >= 0 ? blocks[x][y - 1][z] : BlockType.AIR;
                case 2: // Front
                    if (z + 1 < World.CHUNK_SIZE) {
                        return blocks[x][y][z + 1];
                    } else {
                        // Get block from neighboring chunk
                        return world.getBlockAt(getWorldX(x), y, getWorldZ(z + 1));
                    }
                case 3: // Back
                    if (z - 1 >= 0) {
                        return blocks[x][y][z - 1];
                    } else {
                        // Get block from neighboring chunk
                        return world.getBlockAt(getWorldX(x), y, getWorldZ(z - 1));
                    }
                case 4: // Right
                    if (x + 1 < World.CHUNK_SIZE) {
                        return blocks[x + 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        return world.getBlockAt(getWorldX(x + 1), y, getWorldZ(z));
                    }
                case 5: // Left
                    if (x - 1 >= 0) {
                        return blocks[x - 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        return world.getBlockAt(getWorldX(x - 1), y, getWorldZ(z));
                    }
                default:
                    return BlockType.AIR;
            }
        } catch (Exception e) {
            // If there's any issue getting the adjacent block, assume it's air
            // This prevents crashes when dealing with chunk borders
            return BlockType.AIR;
        }
    }
    
    /**
     * Adds a face to the mesh data.
     */
    private int addFace(int x, int y, int z, int face, BlockType blockType,
                       List<Float> vertices, List<Float> textureCoords,
                       List<Float> normals, List<Float> isWaterFlags, List<Integer> indices, int index) {
        // Convert to world coordinates
        float worldX = x + this.x * World.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + this.z * World.CHUNK_SIZE;
        
        // Define vertices for each face
        switch (face) {
            case 0: // Top face (y+1)
                vertices.add(worldX);        vertices.add(worldY + 1); vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ + 1);
                vertices.add(worldX);        vertices.add(worldY + 1); vertices.add(worldZ + 1);
                
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                break;
                
            case 1: // Bottom face (y-1)
                vertices.add(worldX);        vertices.add(worldY); vertices.add(worldZ);
                vertices.add(worldX);        vertices.add(worldY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY); vertices.add(worldZ);
                
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                break;
                
            case 2: // Front face (z+1)
                vertices.add(worldX);        vertices.add(worldY);     vertices.add(worldZ + 1);
                vertices.add(worldX);        vertices.add(worldY + 1); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ + 1);
                
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                break;
                
            case 3: // Back face (z-1)
                vertices.add(worldX);        vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ);
                vertices.add(worldX);        vertices.add(worldY + 1); vertices.add(worldZ);
                
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                break;
                
            case 4: // Right face (x+1)
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY + 1); vertices.add(worldZ);
                
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                break;
                
            case 5: // Left face (x-1)
                vertices.add(worldX);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX);    vertices.add(worldY + 1); vertices.add(worldZ);
                vertices.add(worldX);    vertices.add(worldY + 1); vertices.add(worldZ + 1);
                vertices.add(worldX);    vertices.add(worldY);     vertices.add(worldZ + 1);
                
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                break;
        }
        
        // Add texture coordinates
        float[] texCoords = blockType.getTextureCoords(face);
        float texX = texCoords[0] / 16.0f;
        float texY = texCoords[1] / 16.0f;
        float texSize = 1.0f / 16.0f;
        
        textureCoords.add(texX);             textureCoords.add(texY);
        textureCoords.add(texX);             textureCoords.add(texY + texSize);
        textureCoords.add(texX + texSize);   textureCoords.add(texY + texSize);
        textureCoords.add(texX + texSize);   textureCoords.add(texY);

        // Add isWater flag for each of the 4 vertices of this face
        float isWaterValue = (blockType == BlockType.WATER) ? 1.0f : 0.0f;
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(isWaterValue);
        }
        
        // Add indices
        indices.add(index);
        indices.add(index + 1);
        indices.add(index + 2);
        
        indices.add(index);
        indices.add(index + 2);
        indices.add(index + 3);
        
        return index + 4;
    }
    
    // Mesh data
    private float[] vertexData;
    private float[] textureData;
    private float[] normalData;
    private float[] isWaterData; // New array for isWater flags
    private int[] indexData;
      /**
     * Creates the OpenGL mesh for this chunk.
     */
    private void createMesh() {
        // Create and bind VAO
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);
        
        // Create and bind vertex VBO
        vertexVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexVboId);
        
        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertexData.length);
        vertexBuffer.put(vertexData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);  // Enable attribute in VAO
        MemoryUtil.memFree(vertexBuffer);
        
        // Create and bind texture VBO
        textureVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, textureVboId);
        
        FloatBuffer textureBuffer = MemoryUtil.memAllocFloat(textureData.length);
        textureBuffer.put(textureData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, textureBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(1);  // Enable attribute in VAO
        MemoryUtil.memFree(textureBuffer);
        
        // Create and bind normal VBO
        normalVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalVboId);
        
        FloatBuffer normalBuffer = MemoryUtil.memAllocFloat(normalData.length);
        normalBuffer.put(normalData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, normalBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(2);  // Enable attribute in VAO
        MemoryUtil.memFree(normalBuffer);

        // Create and bind isWater VBO
        isWaterVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, isWaterVboId);
        FloatBuffer isWaterBuffer = MemoryUtil.memAllocFloat(isWaterData.length);
        isWaterBuffer.put(isWaterData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, isWaterBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 0, 0); // Location 3, 1 float
        GL20.glEnableVertexAttribArray(3); // Enable attribute in VAO
        MemoryUtil.memFree(isWaterBuffer);
        
        // Create and bind index VBO
        indexVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexVboId);
        
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indexData.length);
        indexBuffer.put(indexData).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indexBuffer);
        
        // Unbind VAO
        GL30.glBindVertexArray(0);
    }
      /**
     * Cleans up the mesh data.
     */
    private void cleanupMesh() {
        // Bind the VAO to disable vertex attributes
        GL30.glBindVertexArray(vaoId);
        
        // Disable vertex attribute arrays
        GL20.glDisableVertexAttribArray(0);
        GL20.glDisableVertexAttribArray(1);
        GL20.glDisableVertexAttribArray(2);
        GL20.glDisableVertexAttribArray(3); // Disable isWater attribute
        
        // Unbind VAO
        GL30.glBindVertexArray(0);
        
        // Delete VBOs
        GL15.glDeleteBuffers(vertexVboId);
        GL15.glDeleteBuffers(textureVboId);
        GL15.glDeleteBuffers(normalVboId);
        GL15.glDeleteBuffers(isWaterVboId); // Delete isWater VBO
        GL15.glDeleteBuffers(indexVboId);
        
        // Delete VAO
        GL30.glDeleteVertexArrays(vaoId);
    }
      /**
     * Renders the chunk.
     */
    public void render() {
        if (!meshGenerated || vertexCount == 0) {
            return;
        }
        
        // Bind the VAO - attributes are already enabled in the VAO from createMesh()
        GL30.glBindVertexArray(vaoId);
        
        // Draw the mesh
        GL15.glDrawElements(GL15.GL_TRIANGLES, vertexCount, GL15.GL_UNSIGNED_INT, 0);
        
        // Unbind the VAO
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Converts a local X coordinate to a world X coordinate.
     */
    public int getWorldX(int localX) {
        return x * World.CHUNK_SIZE + localX;
    }
    
    /**
     * Converts a local Z coordinate to a world Z coordinate.
     */
    public int getWorldZ(int localZ) {
        return z * World.CHUNK_SIZE + localZ;
    }

    // Package-private setters to be controlled by World.java
    void setMeshGenerated(boolean meshGenerated) {
        this.meshGenerated = meshGenerated;
    }

    void setDataReadyForGL(boolean dataReadyForGL) {
        this.dataReadyForGL = dataReadyForGL;
    }

    public boolean isMeshGenerated() {
        return meshGenerated;
    }

    public boolean isDataReadyForGL() {
        return dataReadyForGL;
    }

    // Getter for the new flag
    public boolean isMeshDataGenerationScheduledOrInProgress() {
        return meshDataGenerationScheduledOrInProgress;
    }

    // Package-private setter for the new flag, called by World
    void setMeshDataGenerationScheduledOrInProgress(boolean status) {
        this.meshDataGenerationScheduledOrInProgress = status;
    }
      /**
     * Cleans up resources when the chunk is unloaded.
     */
    public void cleanup() {
        if (meshGenerated) {
            cleanupMesh();
            meshGenerated = false;
        }
        
        // Clean up mesh data
        vertexData = null;
        textureData = null;
        normalData = null;
        isWaterData = null; // Nullify isWaterData
        indexData = null;
        vertexCount = 0;
        dataReadyForGL = false;
    }
}
