package com.stonebreak;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

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
      /**
     * Generates the mesh data for the chunk.
     */
    private void generateMeshData(World world) {
        List<Float> vertices = new ArrayList<>();
        List<Float> textureCoords = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> isWaterFlags = new ArrayList<>();
        List<Float> isAlphaTestedFlags = new ArrayList<>(); // New list for isAlphaTested flags
        List<Integer> indices = new ArrayList<>();
        
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
                        index = addFlowerCross(lx, ly, lz, blockType, vertices, textureCoords, normals, isWaterFlags, isAlphaTestedFlags, indices, index);
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
                            index = addFace(lx, ly, lz, face, blockType, vertices, textureCoords, normals, isWaterFlags, isAlphaTestedFlags, indices, index, world);
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
        
        isWaterData = new float[isWaterFlags.size()];
        for (int i = 0; i < isWaterFlags.size(); i++) {
            isWaterData[i] = isWaterFlags.get(i);
        }
        
        isAlphaTestedData = new float[isAlphaTestedFlags.size()]; // Convert isAlphaTestedFlags list to array
        for (int i = 0; i < isAlphaTestedFlags.size(); i++) {
            isAlphaTestedData[i] = isAlphaTestedFlags.get(i);
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
    private int addFace(int x, int y, int z, int face, BlockType blockType,
                       List<Float> vertices, List<Float> textureCoords,
                       List<Float> normals, List<Float> isWaterFlags, List<Float> isAlphaTestedFlags, List<Integer> indices, int index, World world) {
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
                vertices.add(worldX);        vertices.add(topY); vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ + 1);
                vertices.add(worldX);        vertices.add(topY); vertices.add(worldZ + 1);
                
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(1.0f); normals.add(0.0f);
            }
            case 1 -> { // Bottom face (y-1)
                vertices.add(worldX);        vertices.add(worldY); vertices.add(worldZ);
                vertices.add(worldX);        vertices.add(worldY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY); vertices.add(worldZ);
                
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
                normals.add(0.0f); normals.add(-1.0f); normals.add(0.0f);
            }
            case 2 -> { // Front face (z+1)
                float topY = worldY + blockHeight;
                vertices.add(worldX);        vertices.add(worldY);     vertices.add(worldZ + 1);
                vertices.add(worldX);        vertices.add(topY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ + 1);
                
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(1.0f);
            }
            case 3 -> { // Back face (z-1)
                float topY = worldY + blockHeight;
                vertices.add(worldX);        vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ);
                vertices.add(worldX);        vertices.add(topY); vertices.add(worldZ);
                
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
                normals.add(0.0f); normals.add(0.0f); normals.add(-1.0f);
            }
            case 4 -> { // Right face (x+1)
                float topY = worldY + blockHeight;
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX + 1);    vertices.add(worldY);     vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ + 1);
                vertices.add(worldX + 1);    vertices.add(topY); vertices.add(worldZ);
                
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(1.0f); normals.add(0.0f); normals.add(0.0f);
            }
            case 5 -> { // Left face (x-1)
                float topY = worldY + blockHeight;
                vertices.add(worldX);    vertices.add(worldY);     vertices.add(worldZ);
                vertices.add(worldX);    vertices.add(topY); vertices.add(worldZ);
                vertices.add(worldX);    vertices.add(topY); vertices.add(worldZ + 1);
                vertices.add(worldX);    vertices.add(worldY);     vertices.add(worldZ + 1);
                
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
                normals.add(-1.0f); normals.add(0.0f); normals.add(0.0f);
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
                textureCoords.add(u_topLeft); textureCoords.add(v_topLeft);         // V0
                textureCoords.add(u_bottomLeft); textureCoords.add(v_bottomLeft);   // V1
                textureCoords.add(u_bottomRight); textureCoords.add(v_bottomRight); // V2
                textureCoords.add(u_topRight); textureCoords.add(v_topRight);       // V3
            }
            case 2, 5 -> { // Front (+Z) or Left (-X)
                // Vertices for these faces are ordered: BL, TL, TR, BR
                // Desired UVs: BottomLeft, TopLeft, TopRight, BottomRight
                textureCoords.add(u_bottomLeft); textureCoords.add(v_bottomLeft);   // For V0 (BL of face)
                textureCoords.add(u_topLeft); textureCoords.add(v_topLeft);         // For V1 (TL of face)
                textureCoords.add(u_topRight); textureCoords.add(v_topRight);       // For V2 (TR of face)
                textureCoords.add(u_bottomRight); textureCoords.add(v_bottomRight); // For V3 (BR of face)
            }
            case 3 -> { // Back (-Z)
                // Vertices for this face are ordered: BR_face, BL_face, TL_face, TR_face
                // Desired UVs: BottomRight, BottomLeft, TopLeft, TopRight
                textureCoords.add(u_bottomRight); textureCoords.add(v_bottomRight); // For V0 (BR of face)
                textureCoords.add(u_bottomLeft); textureCoords.add(v_bottomLeft);   // For V1 (BL of face)
                textureCoords.add(u_topLeft); textureCoords.add(v_topLeft);         // For V2 (TL of face)
                textureCoords.add(u_topRight); textureCoords.add(v_topRight);       // For V3 (TR of face)
            }
            case 4 -> { // Right (+X)
                // Vertices for this face are ordered: BL_face, BR_face, TR_face, TL_face
                // Desired UVs: BottomLeft, BottomRight, TopRight, TopLeft
                textureCoords.add(u_bottomLeft); textureCoords.add(v_bottomLeft);   // For V0 (BL of face)
                textureCoords.add(u_bottomRight); textureCoords.add(v_bottomRight); // For V1 (BR of face)
                textureCoords.add(u_topRight); textureCoords.add(v_topRight);       // For V2 (TR of face)
                textureCoords.add(u_topLeft); textureCoords.add(v_topLeft);         // For V3 (TL of face)
            }
        }

        // Add isWater flag for each of the 4 vertices of this face
        float isWaterValue = (blockType == BlockType.WATER) ? 1.0f : 0.0f;
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(isWaterValue);
        }
        
        // Add isAlphaTested flag for each of the 4 vertices of this face
        float isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
        for (int i = 0; i < 4; i++) {
            isAlphaTestedFlags.add(isAlphaTestedValue);
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
    
    /**
     * Adds cross-shaped geometry for flower blocks.
     */
    private int addFlowerCross(int x, int y, int z, BlockType blockType,
                              List<Float> vertices, List<Float> textureCoords,
                              List<Float> normals, List<Float> isWaterFlags, List<Float> isAlphaTestedFlags, List<Integer> indices, int index) {
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
        
        // First cross plane (diagonal from NW to SE)
        // Quad 1: NW to SE
        vertices.add(centerX - crossSize); vertices.add(worldY);     vertices.add(centerZ - crossSize); // Bottom NW
        vertices.add(centerX - crossSize); vertices.add(worldY + 1); vertices.add(centerZ - crossSize); // Top NW
        vertices.add(centerX + crossSize); vertices.add(worldY + 1); vertices.add(centerZ + crossSize); // Top SE
        vertices.add(centerX + crossSize); vertices.add(worldY);     vertices.add(centerZ + crossSize); // Bottom SE
        
        // Normals for first plane (pointing towards camera when viewing diagonally)
        float norm1X = 0.707f, norm1Z = -0.707f; // Perpendicular to the NW-SE diagonal
        normals.add(norm1X); normals.add(0.0f); normals.add(norm1Z);
        normals.add(norm1X); normals.add(0.0f); normals.add(norm1Z);
        normals.add(norm1X); normals.add(0.0f); normals.add(norm1Z);
        normals.add(norm1X); normals.add(0.0f); normals.add(norm1Z);
        
        // Texture coordinates for first plane
        textureCoords.add(u_left); textureCoords.add(v_bottom);
        textureCoords.add(u_left); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_bottom);
        
        // IsWater flags for first plane
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(0.0f); // Flowers are not water
        }
        // IsAlphaTested flags for first plane
        for (int i = 0; i < 4; i++) {
            isAlphaTestedFlags.add(1.0f); // Flowers are alpha-tested
        }
        
        // Indices for first plane
        indices.add(index);
        indices.add(index + 1);
        indices.add(index + 2);
        indices.add(index);
        indices.add(index + 2);
        indices.add(index + 3);
        
        index += 4;
        
        // Second cross plane (diagonal from NE to SW) - back face
        vertices.add(centerX + crossSize); vertices.add(worldY);     vertices.add(centerZ - crossSize); // Bottom NE
        vertices.add(centerX + crossSize); vertices.add(worldY + 1); vertices.add(centerZ - crossSize); // Top NE
        vertices.add(centerX - crossSize); vertices.add(worldY + 1); vertices.add(centerZ + crossSize); // Top SW
        vertices.add(centerX - crossSize); vertices.add(worldY);     vertices.add(centerZ + crossSize); // Bottom SW
        
        // Normals for second plane (opposite direction)
        float norm2X = -0.707f, norm2Z = 0.707f;
        normals.add(norm2X); normals.add(0.0f); normals.add(norm2Z);
        normals.add(norm2X); normals.add(0.0f); normals.add(norm2Z);
        normals.add(norm2X); normals.add(0.0f); normals.add(norm2Z);
        normals.add(norm2X); normals.add(0.0f); normals.add(norm2Z);
        
        // Texture coordinates for second plane
        textureCoords.add(u_left); textureCoords.add(v_bottom);
        textureCoords.add(u_left); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_bottom);
        
        // IsWater flags for second plane
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(0.0f);
        }
        // IsAlphaTested flags for second plane
        for (int i = 0; i < 4; i++) {
            isAlphaTestedFlags.add(1.0f);
        }
        
        // Indices for second plane
        indices.add(index);
        indices.add(index + 1);
        indices.add(index + 2);
        indices.add(index);
        indices.add(index + 2);
        indices.add(index + 3);
        
        index += 4;
        
        // Third cross plane (same as first but flipped for double-sided rendering)
        vertices.add(centerX + crossSize); vertices.add(worldY);     vertices.add(centerZ + crossSize); // Bottom SE
        vertices.add(centerX + crossSize); vertices.add(worldY + 1); vertices.add(centerZ + crossSize); // Top SE
        vertices.add(centerX - crossSize); vertices.add(worldY + 1); vertices.add(centerZ - crossSize); // Top NW
        vertices.add(centerX - crossSize); vertices.add(worldY);     vertices.add(centerZ - crossSize); // Bottom NW
        
        // Normals for third plane (opposite of first)
        normals.add(-norm1X); normals.add(0.0f); normals.add(-norm1Z);
        normals.add(-norm1X); normals.add(0.0f); normals.add(-norm1Z);
        normals.add(-norm1X); normals.add(0.0f); normals.add(-norm1Z);
        normals.add(-norm1X); normals.add(0.0f); normals.add(-norm1Z);
        
        // Texture coordinates for third plane
        textureCoords.add(u_left); textureCoords.add(v_bottom);
        textureCoords.add(u_left); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_bottom);
        
        // IsWater flags for third plane
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(0.0f);
        }
        // IsAlphaTested flags for third plane
        for (int i = 0; i < 4; i++) {
            isAlphaTestedFlags.add(1.0f);
        }
        
        // Indices for third plane
        indices.add(index);
        indices.add(index + 1);
        indices.add(index + 2);
        indices.add(index);
        indices.add(index + 2);
        indices.add(index + 3);
        
        index += 4;
        
        // Fourth cross plane (same as second but flipped for double-sided rendering)
        vertices.add(centerX - crossSize); vertices.add(worldY);     vertices.add(centerZ + crossSize); // Bottom SW
        vertices.add(centerX - crossSize); vertices.add(worldY + 1); vertices.add(centerZ + crossSize); // Top SW
        vertices.add(centerX + crossSize); vertices.add(worldY + 1); vertices.add(centerZ - crossSize); // Top NE
        vertices.add(centerX + crossSize); vertices.add(worldY);     vertices.add(centerZ - crossSize); // Bottom NE
        
        // Normals for fourth plane (opposite of second)
        normals.add(-norm2X); normals.add(0.0f); normals.add(-norm2Z);
        normals.add(-norm2X); normals.add(0.0f); normals.add(-norm2Z);
        normals.add(-norm2X); normals.add(0.0f); normals.add(-norm2Z);
        normals.add(-norm2X); normals.add(0.0f); normals.add(-norm2Z);
        
        // Texture coordinates for fourth plane
        textureCoords.add(u_left); textureCoords.add(v_bottom);
        textureCoords.add(u_left); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_top);
        textureCoords.add(u_right); textureCoords.add(v_bottom);
        
        // IsWater flags for fourth plane
        for (int i = 0; i < 4; i++) {
            isWaterFlags.add(0.0f);
        }
        // IsAlphaTested flags for fourth plane
        for (int i = 0; i < 4; i++) {
            isAlphaTestedFlags.add(1.0f);
        }
        
        // Indices for fourth plane
        indices.add(index);
        indices.add(index + 1);
        indices.add(index + 2);
        indices.add(index);
        indices.add(index + 2);
        indices.add(index + 3);
        
        return index + 4; // Return the next available index
    }
    
    // Mesh data
    private float[] vertexData;
    private float[] textureData;
    private float[] normalData;
    private float[] isWaterData;
    private float[] isAlphaTestedData; // New array for isAlphaTested flags
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
        GL20.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 0, 0); // Location 3, 1 float for isWater
        GL20.glEnableVertexAttribArray(3); // Enable attribute in VAO
        MemoryUtil.memFree(isWaterBuffer);

        // Create and bind isAlphaTested VBO
        isAlphaTestedVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, isAlphaTestedVboId);
        FloatBuffer isAlphaTestedBuffer = MemoryUtil.memAllocFloat(isAlphaTestedData.length);
        isAlphaTestedBuffer.put(isAlphaTestedData).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, isAlphaTestedBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, 0, 0); // Location 4, 1 float for isAlphaTested
        GL20.glEnableVertexAttribArray(4); // Enable attribute in VAO
        MemoryUtil.memFree(isAlphaTestedBuffer);
        
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
