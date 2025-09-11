package com.stonebreak.world;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.chunk.mesh.geometry.ChunkMeshOperations;

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
    
    // Chunk mesh operations instance for generating mesh data
    private final ChunkMeshOperations chunkMeshOperations = new ChunkMeshOperations();
    
    /**
     * Builds the mesh data for this chunk. This is CPU-intensive and can be run on a worker thread.
     */
    public void buildAndPrepareMeshData(World world) {
        // meshDataGenerationScheduledOrInProgress is true, set by World.
        try {
            // Update loading progress
            Game game = Game.getInstance();
            if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
                game.getLoadingScreen().updateProgress("Meshing Chunk");
            }
            
            // Generate mesh data directly within the chunk
            ChunkMeshOperations.MeshData meshData = chunkMeshOperations.generateMeshData(blocks, x, z, world);
            
            // Store the generated mesh data
            updateMeshDataFromResult(meshData);
            this.dataReadyForGL = true; // Mark data as ready for GL upload ONLY on success
        } catch (Exception e) {
            System.err.println("CRITICAL: Exception during generateMeshData for chunk (" + x + ", " + z + "): " + e.getMessage());
            System.err.println("Time: " + java.time.LocalDateTime.now());
            System.err.println("Thread: " + Thread.currentThread().getName());
            System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
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
            
            // CRITICAL FIX: Free mesh data arrays after successful GL upload
            // The data is now safely stored in GPU buffers and no longer needed in RAM
            freeMeshDataArrays();
        } catch (Exception e) {
            System.err.println("CRITICAL: Error during createMesh for chunk (" + x + ", " + z + "): " + e.getMessage());
            System.err.println("Time: " + java.time.LocalDateTime.now());
            System.err.println("Thread: " + Thread.currentThread().getName());
            System.err.println("VAO ID: " + this.vaoId + ", VBO ID: " + this.vertexVboId + ", Index Buffer ID: " + this.indexVboId);
            System.err.println("Vertex count: " + this.vertexCount);
            System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            
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
     * Updates mesh data from ChunkMeshOperations result
     */
    private void updateMeshDataFromResult(ChunkMeshOperations.MeshData meshData) {
        // Free old mesh data arrays before assigning new ones
        freeMeshDataArrays();
        
        vertexData = meshData.vertexData;
        textureData = meshData.textureData;
        normalData = meshData.normalData;
        isWaterData = meshData.isWaterData;
        isAlphaTestedData = meshData.isAlphaTestedData;
        indexData = meshData.indexData;
        vertexCount = meshData.vertexCount;
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
          try {
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
                      reusableVertexBuffer = null;
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
                      reusableTextureBuffer = null;
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
                      reusableNormalBuffer = null;
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
                      reusableIsWaterBuffer = null;
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
                      reusableIsAlphaTestedBuffer = null;
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
                      reusableIndexBuffer = null;
                  }
                  reusableIndexBuffer = MemoryUtil.memAllocInt(indexData.length);
              }
              reusableIndexBuffer.clear();
              reusableIndexBuffer.put(indexData).flip();
              GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, reusableIndexBuffer, GL15.GL_STATIC_DRAW);
              
              // Unbind VAO
              GL30.glBindVertexArray(0);
              
          } catch (Exception e) {
              System.err.println("Error creating mesh for chunk (" + x + ", " + z + "): " + e.getMessage());
              // Clean up any partially created buffers on failure
              safeCleanupBuffersOnFailure();
              throw e; // Re-throw to let caller handle
          }
      }
      
      /**
       * Safely cleans up reusable buffers if mesh creation fails.
       */
      private void safeCleanupBuffersOnFailure() {
          try {
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
          } catch (Exception cleanupEx) {
              System.err.println("Error during buffer cleanup: " + cleanupEx.getMessage());
          }
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
      * Cleans up CPU-side resources. Safe to call from any thread.
      */
     public void cleanupCpuResources() {
         freeMeshDataArrays();
         vertexCount = 0;
         dataReadyForGL = false;
         // Reusable buffers are not cleaned here as they are tied to the GL context thread.
     }
 
     /**
      * Cleans up GPU resources. MUST be called from the main OpenGL thread.
      */
     public void cleanupGpuResources() {
         if (meshGenerated) {
             cleanupMesh();
             meshGenerated = false;
         }
 
         // Clean up reusable buffers, as they are direct NIO buffers
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
     }
    
    /**
     * Frees mesh data arrays to reduce memory usage.
     * This is critical for preventing memory leaks when mesh data is regenerated.
     */
    private void freeMeshDataArrays() {
        vertexData = null;
        textureData = null;
        normalData = null;
        isWaterData = null;
        isAlphaTestedData = null;
        indexData = null;
    }
}
