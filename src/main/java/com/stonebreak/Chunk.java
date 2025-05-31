package com.stonebreak;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

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
    private int isWaterVboId; // VBO for isWater flag
    private int isAlphaTestedVboId; // New VBO for isAlphaTested flag
    private int indexVboId;
    private int vertexCount;
    private boolean meshGenerated;
    private volatile boolean dataReadyForGL = false; // Added flag
    private volatile boolean meshDataGenerationScheduledOrInProgress = false; // New flag
    private boolean featuresPopulated = false; // New flag for feature population status
    
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
            // e.printStackTrace(); // Print stack trace for better debugging
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
                this.vaoId = 0; this.vertexVboId = 0; this.textureVboId = 0; this.normalVboId = 0; this.isWaterVboId = 0; this.isAlphaTestedVboId = 0; this.indexVboId = 0;
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
        int tempOldIsWaterVboId = this.isWaterVboId;
        int tempOldIsAlphaTestedVboId = this.isAlphaTestedVboId; // Store old isAlphaTestedVboId
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
                     GL15.glDeleteBuffers(tempOldIsWaterVboId);
                     GL15.glDeleteBuffers(tempOldIsAlphaTestedVboId); // Delete old isAlphaTestedVboId
                     GL15.glDeleteBuffers(tempOldIndexVboId);
                } else if (tempOldVaoId != 0 && tempOldVaoId == this.vaoId) {
                    // This is an unexpected state, log a warning.
                    System.err.println("Warning: Old VAO ID " + tempOldVaoId + " is same as new VAO ID " + this.vaoId +
                                       " for chunk (" + x + ", " + z + ") after mesh creation. Old mesh not explicitly deleted to protect new mesh.");
                }
            }
        } catch (Exception e) {
            System.err.println("CRITICAL: Error during createMesh for chunk (" + x + ", " + z + "): " + e.getMessage());
            // e.printStackTrace();
            
            // createMesh failed. this.vaoId etc. might now hold IDs of partially created, invalid GL objects.
            // These new, failed GL objects need to be cleaned up. cleanupMesh() uses this.vaoId etc.
            // It's important that createMesh() sets this.vaoId etc. to 0 or valid new IDs before throwing.
            // If createMesh sets IDs before potential failure points, cleanupMesh() will target the new, bad IDs.
            if (this.vaoId != tempOldVaoId || this.vertexVboId != tempOldVertexVboId || this.isWaterVboId != tempOldIsWaterVboId || this.isAlphaTestedVboId != tempOldIsAlphaTestedVboId ) { // Check if createMesh changed any ID before failing
                 cleanupMesh(); // This will attempt to delete the new, partially created GL objects.
            }

            // Attempt to restore the old valid mesh's IDs and state if an old mesh existed.
            if (oldMeshWasActuallyGenerated) {
                this.vaoId = tempOldVaoId;
                this.vertexVboId = tempOldVertexVboId;
                this.textureVboId = tempOldTextureVboId;
                this.normalVboId = tempOldNormalVboId;
                this.isWaterVboId = tempOldIsWaterVboId;
                this.isAlphaTestedVboId = tempOldIsAlphaTestedVboId; // Restore old isAlphaTestedVboId
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
                this.vaoId = 0; this.vertexVboId = 0; this.textureVboId = 0; this.normalVboId = 0; this.isWaterVboId = 0; this.isAlphaTestedVboId = 0; this.indexVboId = 0;
           }
       } finally {
            this.dataReadyForGL = false; // Data has been processed (or attempt failed)
        }
    }
      // Reusable arrays for mesh generation to avoid allocations
    private float[] tempVertices = new float[65536]; // Pre-allocated with reasonable size
    private float[] tempTextureCoords = new float[43690]; // 2/3 of vertices for texture coords
    private float[] tempNormals = new float[65536]; // Same as vertices for normals
    private float[] tempIsWaterFlags = new float[32768]; // Increased size to match vertex capacity / 2
    private float[] tempIsAlphaTestedFlags = new float[32768]; // Increased size to match vertex capacity / 2
    private int[] tempIndices = new int[98304]; // 1.5x vertices for indices
    
    private int vertexIndex = 0;
    private int textureIndex = 0;
    private int normalIndex = 0;
    private int flagIndex = 0;
    private int indexIndex = 0;

    /**
     * Generates the mesh data for the chunk.
     */
    private void generateMeshData(World world) {
        // Reset counters for reusable arrays
        vertexIndex = 0;
        textureIndex = 0;
        normalIndex = 0;
        flagIndex = 0;
        indexIndex = 0;
        
        int index = 0;
        
        // Iterate through all blocks in the chunk
        for (int lx = 0; lx < World.CHUNK_SIZE; lx++) {
            for (int ly = 0; ly < World.WORLD_HEIGHT; ly++) {
                for (int lz = 0; lz < World.CHUNK_SIZE; lz++) {
                    BlockType blockType = blocks[lx][ly][lz];
                    
                    // Skip air blocks
                    if (blockType == BlockType.AIR) {
                        continue;
                    }
                    
                    // Handle flowers with cross-shaped geometry
                    if (blockType == BlockType.ROSE || blockType == BlockType.DANDELION) {
                        index = addFlowerCross(lx, ly, lz, blockType, index);
                        continue;
                    }
                    
                    // Check each face of the block
                    for (int face = 0; face < 6; face++) {
                        // Check if the adjacent block is solid
                        BlockType adjacentBlock = getAdjacentBlock(lx, ly, lz, face, world);
                        
                        // Determine if the face should be rendered
                        boolean renderFace;
                        
                        if (blockType == BlockType.WATER) {
                            // For water blocks, always render faces except when adjacent to the same water level
                            if (adjacentBlock == BlockType.WATER) {
                                // Check if we should render between water blocks based on water levels
                                WaterEffects waterEffects = Game.getWaterEffects();
                                if (waterEffects != null) {
                                    int currentWaterLevel = waterEffects.getWaterLevel(getWorldX(lx), ly, getWorldZ(lz));
                                    
                                    // Get adjacent block's world coordinates for level check
                                    int adjWorldX = getWorldX(lx);
                                    int adjWorldY = ly;
                                    int adjWorldZ = getWorldZ(lz);
                                    
                                    switch (face) {
                                        case 0 -> adjWorldY += 1; // Top
                                        case 1 -> adjWorldY -= 1; // Bottom
                                        case 2 -> adjWorldZ += 1; // Front
                                        case 3 -> adjWorldZ -= 1; // Back
                                        case 4 -> adjWorldX += 1; // Right
                                        case 5 -> adjWorldX -= 1; // Left
                                    }
                                    
                                    int adjacentWaterLevel = waterEffects.getWaterLevel(adjWorldX, adjWorldY, adjWorldZ);
                                    
                                    // Render face if water levels are different or if one is a source
                                    renderFace = currentWaterLevel != adjacentWaterLevel || 
                                                waterEffects.isWaterSource(getWorldX(lx), ly, getWorldZ(lz)) ||
                                                waterEffects.isWaterSource(adjWorldX, adjWorldY, adjWorldZ);
                                } else {
                                    // Fallback: render all water faces if water effects not available
                                    renderFace = true;
                                }
                            } else {
                                // Water vs non-water: render if adjacent is transparent or air
                                renderFace = adjacentBlock.isTransparent();
                            }
                        } else {
                            // For non-water blocks, use the original logic
                            // A face of blockType should be rendered if:
                            // 1. The adjacentBlock is transparent, AND (blockType is not WATER OR adjacentBlock is not WATER)
                            //    This handles solid vs transparent, transparent vs different transparent, and water vs non-water transparent.
                            // 2. OR, if blockType itself is transparent AND the adjacentBlock is solid.
                            //    This handles transparent (like leaves/water) vs solid.
                            renderFace = (adjacentBlock.isTransparent() && (blockType != BlockType.WATER || adjacentBlock != BlockType.WATER)) ||
                                         (blockType.isTransparent() && !adjacentBlock.isTransparent());
                        }

                        if (renderFace) {
                            // Add face vertices, texture coordinates, normals, isWater flags, isAlphaTested flags, and indices
                            index = addFace(lx, ly, lz, face, blockType, index, world);
                        }
                    }
                }
            }
        }
        
        // Copy from pre-allocated arrays to final arrays (only the used portions)
        if (vertexIndex > 0) {
            // Safety checks to prevent array overruns
            if (vertexIndex > tempVertices.length || textureIndex > tempTextureCoords.length || 
                normalIndex > tempNormals.length || flagIndex > tempIsWaterFlags.length ||
                flagIndex > tempIsAlphaTestedFlags.length || indexIndex > tempIndices.length) {
                System.err.println("CRITICAL: Array indices exceed bounds during mesh data copy for chunk (" + x + ", " + z + ")");
                System.err.println("Vertex: " + vertexIndex + "/" + tempVertices.length + 
                                 ", Texture: " + textureIndex + "/" + tempTextureCoords.length +
                                 ", Normal: " + normalIndex + "/" + tempNormals.length +
                                 ", Flag: " + flagIndex + "/" + tempIsWaterFlags.length +
                                 ", Index: " + indexIndex + "/" + tempIndices.length);
                // Set to empty arrays to prevent crash
                vertexData = new float[0];
                textureData = new float[0];
                normalData = new float[0];
                isWaterData = new float[0];
                isAlphaTestedData = new float[0];
                indexData = new int[0];
                vertexCount = 0;
                return;
            }
            
            vertexData = new float[vertexIndex];
            System.arraycopy(tempVertices, 0, vertexData, 0, vertexIndex);
            
            textureData = new float[textureIndex];
            System.arraycopy(tempTextureCoords, 0, textureData, 0, textureIndex);
            
            normalData = new float[normalIndex];
            System.arraycopy(tempNormals, 0, normalData, 0, normalIndex);
            
            isWaterData = new float[flagIndex];
            System.arraycopy(tempIsWaterFlags, 0, isWaterData, 0, flagIndex);
            
            isAlphaTestedData = new float[flagIndex];
            System.arraycopy(tempIsAlphaTestedFlags, 0, isAlphaTestedData, 0, flagIndex);
            
            indexData = new int[indexIndex];
            System.arraycopy(tempIndices, 0, indexData, 0, indexIndex);
            
            vertexCount = indexIndex;
        } else {
            // No mesh data generated
            vertexData = new float[0];
            textureData = new float[0];
            normalData = new float[0];
            isWaterData = new float[0];
            isAlphaTestedData = new float[0];
            indexData = new int[0];
            vertexCount = 0;
        }
    }    /**
     * Gets the block adjacent to the specified position in the given direction.
     */
    private BlockType getAdjacentBlock(int x, int y, int z, int face, World world) {
        // Determine adjacent block coordinates based on the face
        // 0: Top, 1: Bottom, 2: Front, 3: Back, 4: Right, 5: Left
        try {
            return switch (face) {
                case 0 -> // Top
                    y + 1 < World.WORLD_HEIGHT ? blocks[x][y + 1][z] : BlockType.AIR;
                case 1 -> // Bottom
                    y - 1 >= 0 ? blocks[x][y - 1][z] : BlockType.AIR;
                case 2 -> { // Front
                    if (z + 1 < World.CHUNK_SIZE) {
                        yield blocks[x][y][z + 1];
                    } else {
                        // Get block from neighboring chunk
                        yield world.getBlockAt(getWorldX(x), y, getWorldZ(z + 1));
                    }
                }
                case 3 -> { // Back
                    if (z - 1 >= 0) {
                        yield blocks[x][y][z - 1];
                    } else {
                        // Get block from neighboring chunk
                        yield world.getBlockAt(getWorldX(x), y, getWorldZ(z - 1));
                    }
                }
                case 4 -> { // Right
                    if (x + 1 < World.CHUNK_SIZE) {
                        yield blocks[x + 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        yield world.getBlockAt(getWorldX(x + 1), y, getWorldZ(z));
                    }
                }
                case 5 -> { // Left
                    if (x - 1 >= 0) {
                        yield blocks[x - 1][y][z];
                    } else {
                        // Get block from neighboring chunk
                        yield world.getBlockAt(getWorldX(x - 1), y, getWorldZ(z));
                    }
                }
                default -> BlockType.AIR;
            };
        } catch (Exception e) {
            // If there's any issue getting the adjacent block, assume it's air
            // This prevents crashes when dealing with chunk borders
            return BlockType.AIR;
        }
    }
    
    /**
     * Adds a face to the mesh data.
     */
    private int addFace(int x, int y, int z, int face, BlockType blockType, int index, World world) {
        // Check if we have enough space in our pre-allocated arrays
        if (vertexIndex + 12 > tempVertices.length || 
            textureIndex + 8 > tempTextureCoords.length ||
            normalIndex + 12 > tempNormals.length ||
            flagIndex + 4 > tempIsWaterFlags.length ||
            flagIndex + 4 > tempIsAlphaTestedFlags.length ||
            indexIndex + 6 > tempIndices.length) {
            // Arrays are full, skip this face to avoid overflow
            System.err.println("Warning: Chunk mesh arrays full, skipping face. Vertex: " + vertexIndex + ", Texture: " + textureIndex + ", Normal: " + normalIndex + ", Flag: " + flagIndex + ", Index: " + indexIndex);
            return index;
        }
        // Convert to world coordinates
        float worldX = x + this.x * World.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + this.z * World.CHUNK_SIZE;
        
        // Get visual height for blocks that can have variable heights
        float blockHeight = 1.0f; // Default full height
        if (blockType == BlockType.WATER) {
            WaterEffects waterEffects = Game.getWaterEffects();
            if (waterEffects != null) {
                blockHeight = waterEffects.getWaterVisualHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        } else if (blockType == BlockType.SNOW) {
            // Get snow layer height from world
            if (world != null) {
                blockHeight = world.getSnowHeight((int)worldX, (int)worldY, (int)worldZ);
            }
        }
        
        // Define vertices for each face
        switch (face) {
            case 0 -> { // Top face (y+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 1 -> { // Bottom face (y-1)
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 2 -> { // Front face (z+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 1.0f;
            }
            case 3 -> { // Back face (z-1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;        tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
                tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = -1.0f;
            }
            case 4 -> { // Right face (x+1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX + 1;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                
                // Add normals
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = 1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
            }
            case 5 -> { // Left face (x-1)
                float topY = worldY + blockHeight;
                // Add vertices
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = topY; tempVertices[vertexIndex++] = worldZ + 1;
                tempVertices[vertexIndex++] = worldX;    tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = worldZ + 1;
                
                // Add normals
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
                tempNormals[normalIndex++] = -1.0f; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = 0.0f;
            }
        }
        
        // Add texture coordinates
        float[] texCoords = blockType.getTextureCoords(face);
        float texX = texCoords[0] / 16.0f;
        float texY = texCoords[1] / 16.0f;
        float texSize = 1.0f / 16.0f;
        
        float u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft;
        float u_bottomRight, v_bottomRight, u_topRight, v_topRight;
        
        if (blockType == BlockType.WATER) {
            // For water blocks, use continuous world-space texture coordinates for completely seamless tiling
            // This makes the texture flow continuously across all water blocks without any visible boundaries
            float textureScale = 0.0625f; // Very small scale for large-area seamless appearance
            
            // Use absolute world coordinates to ensure perfect continuity across all water blocks
            // No offsets or per-block adjustments - pure world-space mapping
            
            switch (face) {
                case 0 -> { // Top face - use X,Z world coordinates
                    u_topLeft = worldX * textureScale;
                    v_topLeft = worldZ * textureScale;
                    u_bottomLeft = worldX * textureScale;
                    v_bottomLeft = (worldZ + 1) * textureScale;
                    u_bottomRight = (worldX + 1) * textureScale;
                    v_bottomRight = (worldZ + 1) * textureScale;
                    u_topRight = (worldX + 1) * textureScale;
                    v_topRight = worldZ * textureScale;
                }
                case 1 -> { // Bottom face - use X,Z world coordinates
                    u_topLeft = worldX * textureScale;
                    v_topLeft = worldZ * textureScale;
                    u_bottomLeft = worldX * textureScale;
                    v_bottomLeft = (worldZ + 1) * textureScale;
                    u_bottomRight = (worldX + 1) * textureScale;
                    v_bottomRight = (worldZ + 1) * textureScale;
                    u_topRight = (worldX + 1) * textureScale;
                    v_topRight = worldZ * textureScale;
                }
                case 2, 3 -> { // Front/Back faces - use X,Y world coordinates
                    u_topLeft = worldX * textureScale;
                    v_topLeft = (worldY + blockHeight) * textureScale;
                    u_bottomLeft = worldX * textureScale;
                    v_bottomLeft = worldY * textureScale;
                    u_bottomRight = (worldX + 1) * textureScale;
                    v_bottomRight = worldY * textureScale;
                    u_topRight = (worldX + 1) * textureScale;
                    v_topRight = (worldY + blockHeight) * textureScale;
                }
                case 4, 5 -> { // Left/Right faces - use Z,Y world coordinates  
                    u_topLeft = worldZ * textureScale;
                    v_topLeft = (worldY + blockHeight) * textureScale;
                    u_bottomLeft = worldZ * textureScale;
                    v_bottomLeft = worldY * textureScale;
                    u_bottomRight = (worldZ + 1) * textureScale;
                    v_bottomRight = worldY * textureScale;
                    u_topRight = (worldZ + 1) * textureScale;
                    v_topRight = (worldY + blockHeight) * textureScale;
                }
                default -> {
                    // Fallback to regular texture coordinates
                    u_topLeft = texX;
                    v_topLeft = texY;
                    u_bottomLeft = texX;
                    v_bottomLeft = texY + texSize;
                    u_bottomRight = texX + texSize;
                    v_bottomRight = texY + texSize;
                    u_topRight = texX + texSize;
                    v_topRight = texY;
                }
            }
            
            // Add subtle pseudo-random offset to break up any remaining grid patterns
            // This creates more natural-looking water texture variation
            float noiseScale = 0.03f;
            float noiseU = (float)(Math.sin(worldX * 0.1f + worldZ * 0.17f) * noiseScale);
            float noiseV = (float)(Math.cos(worldX * 0.13f + worldZ * 0.11f) * noiseScale);
            
            // Apply noise offset
            u_topLeft += noiseU;
            v_topLeft += noiseV;
            u_bottomLeft += noiseU;
            v_bottomLeft += noiseV;
            u_bottomRight += noiseU;
            v_bottomRight += noiseV;
            u_topRight += noiseU;
            v_topRight += noiseV;
            
            // Apply fractional part to keep texture coordinates within 0-1 range while maintaining continuity
            u_topLeft = u_topLeft % 1.0f;
            v_topLeft = v_topLeft % 1.0f;
            u_bottomLeft = u_bottomLeft % 1.0f;
            v_bottomLeft = v_bottomLeft % 1.0f;
            u_bottomRight = u_bottomRight % 1.0f;
            v_bottomRight = v_bottomRight % 1.0f;
            u_topRight = u_topRight % 1.0f;
            v_topRight = v_topRight % 1.0f;
            
            // Handle negative values properly for seamless wrapping
            if (u_topLeft < 0) u_topLeft += 1.0f;
            if (v_topLeft < 0) v_topLeft += 1.0f;
            if (u_bottomLeft < 0) u_bottomLeft += 1.0f;
            if (v_bottomLeft < 0) v_bottomLeft += 1.0f;
            if (u_bottomRight < 0) u_bottomRight += 1.0f;
            if (v_bottomRight < 0) v_bottomRight += 1.0f;
            if (u_topRight < 0) u_topRight += 1.0f;
            if (v_topRight < 0) v_topRight += 1.0f;
            
            // Scale to fit within the water texture's atlas coordinates
            u_topLeft = texX + u_topLeft * texSize;
            v_topLeft = texY + v_topLeft * texSize;
            u_bottomLeft = texX + u_bottomLeft * texSize;
            v_bottomLeft = texY + v_bottomLeft * texSize;
            u_bottomRight = texX + u_bottomRight * texSize;
            v_bottomRight = texY + v_bottomRight * texSize;
            u_topRight = texX + u_topRight * texSize;
            v_topRight = texY + v_topRight * texSize;
        } else {
            // For non-water blocks, use regular texture coordinates
            u_topLeft = texX;
            v_topLeft = texY;
            u_bottomLeft = texX;
            v_bottomLeft = texY + texSize;
            u_bottomRight = texX + texSize;
            v_bottomRight = texY + texSize;
            u_topRight = texX + texSize;
            v_topRight = texY;
        }

        // Apply UV mapping based on face orientation
        switch (face) {
            case 0, 1 -> { // Top or Bottom face
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // V0
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // V1
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // V2
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // V3
            }
            case 2, 5 -> { // Front (+Z) or Left (-X)
                // Vertices for these faces are ordered: BL, TL, TR, BR
                // Desired UVs: BottomLeft, TopLeft, TopRight, BottomRight
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V1 (TL of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V2 (TR of face)
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V3 (BR of face)
            }
            case 3 -> { // Back (-Z)
                // Vertices for this face are ordered: BR_face, BL_face, TL_face, TR_face
                // Desired UVs: BottomRight, BottomLeft, TopLeft, TopRight
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V0 (BR of face)
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V1 (BL of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V2 (TL of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V3 (TR of face)
            }
            case 4 -> { // Right (+X)
                // Vertices for this face are ordered: BL_face, BR_face, TR_face, TL_face
                // Desired UVs: BottomLeft, BottomRight, TopRight, TopLeft
                tempTextureCoords[textureIndex++] = u_bottomLeft; tempTextureCoords[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                tempTextureCoords[textureIndex++] = u_bottomRight; tempTextureCoords[textureIndex++] = v_bottomRight; // For V1 (BR of face)
                tempTextureCoords[textureIndex++] = u_topRight; tempTextureCoords[textureIndex++] = v_topRight;       // For V2 (TR of face)
                tempTextureCoords[textureIndex++] = u_topLeft; tempTextureCoords[textureIndex++] = v_topLeft;         // For V3 (TL of face)
            }
        }

        // Add isWater flag for each of the 4 vertices of this face
        float isWaterValue = (blockType == BlockType.WATER) ? 1.0f : 0.0f;
        tempIsWaterFlags[flagIndex] = isWaterValue;
        tempIsWaterFlags[flagIndex + 1] = isWaterValue;
        tempIsWaterFlags[flagIndex + 2] = isWaterValue;
        tempIsWaterFlags[flagIndex + 3] = isWaterValue;
        
        // Add isAlphaTested flag for each of the 4 vertices of this face
        float isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
        tempIsAlphaTestedFlags[flagIndex] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 1] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 2] = isAlphaTestedValue;
        tempIsAlphaTestedFlags[flagIndex + 3] = isAlphaTestedValue;
        
        flagIndex += 4; // Move flag index forward by 4
        
        // Add indices
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        return index + 4;
    }
    
    /**
     * Adds cross-shaped geometry for flower blocks.
     */
    private int addFlowerCross(int x, int y, int z, BlockType blockType, int index) {
        // Check if we have enough space in our pre-allocated arrays for flower geometry (8 vertices)
        if (vertexIndex + 24 > tempVertices.length || 
            textureIndex + 16 > tempTextureCoords.length ||
            normalIndex + 24 > tempNormals.length ||
            flagIndex + 8 > tempIsWaterFlags.length ||
            flagIndex + 8 > tempIsAlphaTestedFlags.length ||
            indexIndex + 24 > tempIndices.length) {
            // Arrays are full, skip this flower to avoid overflow
            System.err.println("Warning: Chunk mesh arrays full, skipping flower. Vertex: " + vertexIndex + ", Texture: " + textureIndex + ", Normal: " + normalIndex + ", Flag: " + flagIndex + ", Index: " + indexIndex);
            return index;
        }
        // Convert to world coordinates
        float worldX = x + this.x * World.CHUNK_SIZE;
        float worldY = y;
        float worldZ = z + this.z * World.CHUNK_SIZE;
        
        // Offset for centering the cross in the block
        float centerX = worldX + 0.5f;
        float centerZ = worldZ + 0.5f;
        float crossSize = 0.45f; // Slightly smaller than full block
        
        // Get texture coordinates for the flower
        float[] texCoords = blockType.getTextureCoords(0); // Use face 0 texture
        float texX = texCoords[0] / 16.0f;
        float texY = texCoords[1] / 16.0f;
        float texSize = 1.0f / 16.0f;
        
        float u_left = texX;
        float v_top = texY;
        float u_right = texX + texSize;
        float v_bottom = texY + texSize;
        
        // Only create 2 cross planes (no duplicates for double-sided)
        // First cross plane (diagonal from NW to SE)
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ - crossSize; // Bottom NW
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ - crossSize; // Top NW
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ + crossSize; // Top SE
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ + crossSize; // Bottom SE
        
        // Normals for first plane
        float norm1X = 0.707f, norm1Z = -0.707f;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        tempNormals[normalIndex++] = norm1X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm1Z;
        
        // Texture coordinates for first plane
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_bottom;
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_bottom;
        
        // Flags for first plane
        tempIsWaterFlags[flagIndex] = 0.0f;
        tempIsWaterFlags[flagIndex + 1] = 0.0f;
        tempIsWaterFlags[flagIndex + 2] = 0.0f;
        tempIsWaterFlags[flagIndex + 3] = 0.0f;
        tempIsAlphaTestedFlags[flagIndex] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 1] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 2] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 3] = 1.0f;
        flagIndex += 4;
        
        // Indices for first plane (front-facing)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        // Indices for first plane (back-facing with reversed winding)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 3;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 1;
        
        index += 4;
        
        // Second cross plane (diagonal from NE to SW)
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ - crossSize; // Bottom NE
        tempVertices[vertexIndex++] = centerX + crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ - crossSize; // Top NE
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY + 1; tempVertices[vertexIndex++] = centerZ + crossSize; // Top SW
        tempVertices[vertexIndex++] = centerX - crossSize; tempVertices[vertexIndex++] = worldY;     tempVertices[vertexIndex++] = centerZ + crossSize; // Bottom SW
        
        // Normals for second plane
        float norm2X = -0.707f, norm2Z = 0.707f;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        tempNormals[normalIndex++] = norm2X; tempNormals[normalIndex++] = 0.0f; tempNormals[normalIndex++] = norm2Z;
        
        // Texture coordinates for second plane
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_bottom;
        tempTextureCoords[textureIndex++] = u_left; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_top;
        tempTextureCoords[textureIndex++] = u_right; tempTextureCoords[textureIndex++] = v_bottom;
        
        // Flags for second plane
        tempIsWaterFlags[flagIndex] = 0.0f;
        tempIsWaterFlags[flagIndex + 1] = 0.0f;
        tempIsWaterFlags[flagIndex + 2] = 0.0f;
        tempIsWaterFlags[flagIndex + 3] = 0.0f;
        tempIsAlphaTestedFlags[flagIndex] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 1] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 2] = 1.0f;
        tempIsAlphaTestedFlags[flagIndex + 3] = 1.0f;
        flagIndex += 4;
        
        // Indices for second plane (front-facing)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 1;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 3;
        
        // Indices for second plane (back-facing with reversed winding)
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 3;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index;
        tempIndices[indexIndex++] = index + 2;
        tempIndices[indexIndex++] = index + 1;
        
        return index + 4; // Return the next available index
    }
    
    // Mesh data
    private float[] vertexData;
    private float[] textureData;
    private float[] normalData;
    private float[] isWaterData;
    private float[] isAlphaTestedData; // New array for isAlphaTested flags
    private int[] indexData;
    
    // Reusable buffers for OpenGL operations to reduce allocations
    private FloatBuffer reusableVertexBuffer;
    private FloatBuffer reusableTextureBuffer;
    private FloatBuffer reusableNormalBuffer;
    private FloatBuffer reusableIsWaterBuffer;
    private FloatBuffer reusableIsAlphaTestedBuffer;
    private IntBuffer reusableIndexBuffer;
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
        
        // Reuse or create vertex buffer
        if (reusableVertexBuffer == null || reusableVertexBuffer.capacity() < vertexData.length) {
            if (reusableVertexBuffer != null) {
                MemoryUtil.memFree(reusableVertexBuffer);
            }
            reusableVertexBuffer = MemoryUtil.memAllocFloat(vertexData.length);
        }
        reusableVertexBuffer.clear();
        reusableVertexBuffer.put(vertexData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, reusableVertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);  // Enable attribute in VAO
        
        // Create and bind texture VBO
        textureVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, textureVboId);
        
        // Reuse or create texture buffer
        if (reusableTextureBuffer == null || reusableTextureBuffer.capacity() < textureData.length) {
            if (reusableTextureBuffer != null) {
                MemoryUtil.memFree(reusableTextureBuffer);
            }
            reusableTextureBuffer = MemoryUtil.memAllocFloat(textureData.length);
        }
        reusableTextureBuffer.clear();
        reusableTextureBuffer.put(textureData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, reusableTextureBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(1);  // Enable attribute in VAO
        
        // Create and bind normal VBO
        normalVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalVboId);
        
        // Reuse or create normal buffer
        if (reusableNormalBuffer == null || reusableNormalBuffer.capacity() < normalData.length) {
            if (reusableNormalBuffer != null) {
                MemoryUtil.memFree(reusableNormalBuffer);
            }
            reusableNormalBuffer = MemoryUtil.memAllocFloat(normalData.length);
        }
        reusableNormalBuffer.clear();
        reusableNormalBuffer.put(normalData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, reusableNormalBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(2);  // Enable attribute in VAO

        // Create and bind isWater VBO
        isWaterVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, isWaterVboId);
        
        // Reuse or create isWater buffer
        if (reusableIsWaterBuffer == null || reusableIsWaterBuffer.capacity() < isWaterData.length) {
            if (reusableIsWaterBuffer != null) {
                MemoryUtil.memFree(reusableIsWaterBuffer);
            }
            reusableIsWaterBuffer = MemoryUtil.memAllocFloat(isWaterData.length);
        }
        reusableIsWaterBuffer.clear();
        reusableIsWaterBuffer.put(isWaterData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, reusableIsWaterBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 0, 0); // Location 3, 1 float for isWater
        GL20.glEnableVertexAttribArray(3); // Enable attribute in VAO

        // Create and bind isAlphaTested VBO
        isAlphaTestedVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, isAlphaTestedVboId);
        
        // Reuse or create isAlphaTested buffer
        if (reusableIsAlphaTestedBuffer == null || reusableIsAlphaTestedBuffer.capacity() < isAlphaTestedData.length) {
            if (reusableIsAlphaTestedBuffer != null) {
                MemoryUtil.memFree(reusableIsAlphaTestedBuffer);
            }
            reusableIsAlphaTestedBuffer = MemoryUtil.memAllocFloat(isAlphaTestedData.length);
        }
        reusableIsAlphaTestedBuffer.clear();
        reusableIsAlphaTestedBuffer.put(isAlphaTestedData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, reusableIsAlphaTestedBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, 0, 0); // Location 4, 1 float for isAlphaTested
        GL20.glEnableVertexAttribArray(4); // Enable attribute in VAO
        
        // Create and bind index VBO
        indexVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexVboId);
        
        // Reuse or create index buffer
        if (reusableIndexBuffer == null || reusableIndexBuffer.capacity() < indexData.length) {
            if (reusableIndexBuffer != null) {
                MemoryUtil.memFree(reusableIndexBuffer);
            }
            reusableIndexBuffer = MemoryUtil.memAllocInt(indexData.length);
        }
        reusableIndexBuffer.clear();
        reusableIndexBuffer.put(indexData).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, reusableIndexBuffer, GL15.GL_STATIC_DRAW);
        
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
        GL20.glDisableVertexAttribArray(4); // Disable isAlphaTested attribute
        
        // Unbind VAO
        GL30.glBindVertexArray(0);
        
        // Delete VBOs
        GL15.glDeleteBuffers(vertexVboId);
        GL15.glDeleteBuffers(textureVboId);
        GL15.glDeleteBuffers(normalVboId);
        GL15.glDeleteBuffers(isWaterVboId);
        GL15.glDeleteBuffers(isAlphaTestedVboId); // Delete isAlphaTested VBO
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

    public int getChunkX() {
        return this.x;
    }

    public int getChunkZ() {
        return this.z;
    }

    public boolean areFeaturesPopulated() {
        return featuresPopulated;
    }

    public void setFeaturesPopulated(boolean featuresPopulated) {
        this.featuresPopulated = featuresPopulated;
    }

    // Package-private setters to be controlled by World.java
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
        
        // Clean up reusable buffers
        if (reusableVertexBuffer != null) {
            MemoryUtil.memFree(reusableVertexBuffer);
            reusableVertexBuffer = null;
        }
        if (reusableTextureBuffer != null) {
            MemoryUtil.memFree(reusableTextureBuffer);
            reusableTextureBuffer = null;
        }
        if (reusableNormalBuffer != null) {
            MemoryUtil.memFree(reusableNormalBuffer);
            reusableNormalBuffer = null;
        }
        if (reusableIsWaterBuffer != null) {
            MemoryUtil.memFree(reusableIsWaterBuffer);
            reusableIsWaterBuffer = null;
        }
        if (reusableIsAlphaTestedBuffer != null) {
            MemoryUtil.memFree(reusableIsAlphaTestedBuffer);
            reusableIsAlphaTestedBuffer = null;
        }
        if (reusableIndexBuffer != null) {
            MemoryUtil.memFree(reusableIndexBuffer);
            reusableIndexBuffer = null;
        }
        
        // Clean up mesh data
        vertexData = null;
        textureData = null;
        normalData = null;
        isWaterData = null;
        isAlphaTestedData = null; // Nullify isAlphaTestedData
        indexData = null;
        vertexCount = 0;
        dataReadyForGL = false;
    }
}
