package com.stonebreak.world.chunk.operations;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Operations for managing OpenGL buffers for chunk rendering.
 * Handles VAO/VBO creation, updates, and deletion with proper resource management.
 * MUST be called only from the main OpenGL thread.
 */
public class ChunkBufferOperations {
    
    private static final Logger logger = Logger.getLogger(ChunkBufferOperations.class.getName());
    
    // Reusable buffers for OpenGL operations to reduce allocations
    private FloatBuffer reusableVertexBuffer;
    private FloatBuffer reusableTextureBuffer;
    private FloatBuffer reusableNormalBuffer;
    private FloatBuffer reusableIsWaterBuffer;
    private FloatBuffer reusableIsAlphaTestedBuffer;
    private IntBuffer reusableIndexBuffer;
    
    /**
     * Creates new OpenGL buffers and uploads the mesh data.
     * @param meshData The mesh data to upload
     * @return ChunkBufferState with the created buffer IDs
     * @throws RuntimeException if buffer creation fails
     */
    public ChunkBufferState createBuffers(ChunkMeshData meshData) {
        if (meshData.isEmpty()) {
            return ChunkBufferState.empty();
        }
        
        int vaoId = 0;
        int vertexVboId = 0;
        int textureVboId = 0;
        int normalVboId = 0;
        int isWaterVboId = 0;
        int isAlphaTestedVboId = 0;
        int indexVboId = 0;
        
        try {
            // Create and bind VAO
            vaoId = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vaoId);
            
            // Create vertex buffer
            vertexVboId = createAndUploadFloatBuffer(meshData.getVertexData(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(0);
            
            // Create texture coordinate buffer
            textureVboId = createAndUploadFloatBuffer(meshData.getTextureData(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(1);
            
            // Create normal buffer
            normalVboId = createAndUploadFloatBuffer(meshData.getNormalData(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(2);
            
            // Create water flag buffer
            isWaterVboId = createAndUploadFloatBuffer(meshData.getIsWaterData(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(3);
            
            // Create alpha test flag buffer
            isAlphaTestedVboId = createAndUploadFloatBuffer(meshData.getIsAlphaTestedData(), GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(4, 1, GL20.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(4);
            
            // Create index buffer
            indexVboId = createAndUploadIntBuffer(meshData.getIndexData(), GL15.GL_STATIC_DRAW);
            
            // Unbind VAO
            GL30.glBindVertexArray(0);
            
            return new ChunkBufferState(vaoId, vertexVboId, textureVboId, normalVboId, 
                                      isWaterVboId, isAlphaTestedVboId, indexVboId);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create OpenGL buffers for chunk mesh", e);
            
            // Clean up any partially created buffers
            safeDeleteBuffer(vertexVboId);
            safeDeleteBuffer(textureVboId);
            safeDeleteBuffer(normalVboId);
            safeDeleteBuffer(isWaterVboId);
            safeDeleteBuffer(isAlphaTestedVboId);
            safeDeleteBuffer(indexVboId);
            safeDeleteVertexArray(vaoId);
            
            throw new RuntimeException("Failed to create OpenGL buffers", e);
        }
    }
    
    /**
     * Updates existing buffers with new mesh data.
     * @param bufferState The existing buffer state
     * @param meshData The new mesh data to upload
     * @throws IllegalArgumentException if buffer state is invalid
     * @throws RuntimeException if buffer update fails
     */
    public void updateBuffers(ChunkBufferState bufferState, ChunkMeshData meshData) {
        if (!bufferState.isValid()) {
            throw new IllegalArgumentException("Buffer state is invalid - cannot update");
        }
        
        if (meshData.isEmpty()) {
            // Handle empty mesh by clearing buffers but keeping them allocated
            return;
        }
        
        try {
            // Bind VAO
            GL30.glBindVertexArray(bufferState.getVaoId());
            
            // Update vertex buffer
            updateFloatBuffer(bufferState.getVertexVboId(), meshData.getVertexData());
            
            // Update texture buffer
            updateFloatBuffer(bufferState.getTextureVboId(), meshData.getTextureData());
            
            // Update normal buffer
            updateFloatBuffer(bufferState.getNormalVboId(), meshData.getNormalData());
            
            // Update water flag buffer
            updateFloatBuffer(bufferState.getIsWaterVboId(), meshData.getIsWaterData());
            
            // Update alpha test flag buffer
            updateFloatBuffer(bufferState.getIsAlphaTestedVboId(), meshData.getIsAlphaTestedData());
            
            // Update index buffer
            updateIntBuffer(bufferState.getIndexVboId(), meshData.getIndexData());
            
            // Unbind VAO
            GL30.glBindVertexArray(0);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to update OpenGL buffers: " + bufferState, e);
            throw new RuntimeException("Failed to update OpenGL buffers", e);
        }
    }
    
    /**
     * Deletes all OpenGL buffers referenced by the buffer state.
     * @param bufferState The buffer state containing IDs to delete
     */
    public void deleteBuffers(ChunkBufferState bufferState) {
        if (!bufferState.isValid()) {
            return; // Nothing to delete
        }
        
        try {
            // Bind the VAO to disable vertex attributes
            GL30.glBindVertexArray(bufferState.getVaoId());
            
            // Disable vertex attribute arrays
            GL20.glDisableVertexAttribArray(0);
            GL20.glDisableVertexAttribArray(1);
            GL20.glDisableVertexAttribArray(2);
            GL20.glDisableVertexAttribArray(3);
            GL20.glDisableVertexAttribArray(4);
            
            // Unbind VAO
            GL30.glBindVertexArray(0);
            
            // Delete all buffers
            GL15.glDeleteBuffers(bufferState.getVertexVboId());
            GL15.glDeleteBuffers(bufferState.getTextureVboId());
            GL15.glDeleteBuffers(bufferState.getNormalVboId());
            GL15.glDeleteBuffers(bufferState.getIsWaterVboId());
            GL15.glDeleteBuffers(bufferState.getIsAlphaTestedVboId());
            GL15.glDeleteBuffers(bufferState.getIndexVboId());
            
            // Delete VAO
            GL30.glDeleteVertexArrays(bufferState.getVaoId());
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error during buffer cleanup: " + bufferState, e);
            // Continue with cleanup - this is a best-effort operation
        }
    }
    
    /**
     * Renders the mesh using the buffer state.
     * @param bufferState The buffer state to render
     * @param indexCount Number of indices to render
     */
    public void render(ChunkBufferState bufferState, int indexCount) {
        if (!bufferState.isValid() || indexCount == 0) {
            return;
        }
        
        // Bind the VAO - attributes are already enabled from creation
        GL30.glBindVertexArray(bufferState.getVaoId());
        
        // Draw the mesh
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);
        
        // Unbind the VAO
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Cleans up reusable buffers. Should be called during shutdown.
     */
    public void cleanup() {
        freeReusableBuffer(reusableVertexBuffer);
        freeReusableBuffer(reusableTextureBuffer);
        freeReusableBuffer(reusableNormalBuffer);
        freeReusableBuffer(reusableIsWaterBuffer);
        freeReusableBuffer(reusableIsAlphaTestedBuffer);
        freeReusableBuffer(reusableIndexBuffer);
        
        reusableVertexBuffer = null;
        reusableTextureBuffer = null;
        reusableNormalBuffer = null;
        reusableIsWaterBuffer = null;
        reusableIsAlphaTestedBuffer = null;
        reusableIndexBuffer = null;
    }
    
    /**
     * Creates a VBO and uploads float data to it.
     */
    private int createAndUploadFloatBuffer(float[] data, int usage) {
        int vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        
        // Get or create reusable buffer
        FloatBuffer buffer = getOrCreateFloatBuffer(data.length);
        buffer.clear();
        buffer.put(data).flip();
        
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, usage);
        return vboId;
    }
    
    /**
     * Creates an index buffer and uploads int data to it.
     */
    private int createAndUploadIntBuffer(int[] data, int usage) {
        int vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vboId);
        
        // Get or create reusable buffer
        if (reusableIndexBuffer == null || reusableIndexBuffer.capacity() < data.length) {
            freeReusableBuffer(reusableIndexBuffer);
            reusableIndexBuffer = MemoryUtil.memAllocInt(data.length);
        }
        
        reusableIndexBuffer.clear();
        reusableIndexBuffer.put(data).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, reusableIndexBuffer, usage);
        return vboId;
    }
    
    /**
     * Updates an existing float buffer with new data.
     */
    private void updateFloatBuffer(int vboId, float[] data) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        
        FloatBuffer buffer = getOrCreateFloatBuffer(data.length);
        buffer.clear();
        buffer.put(data).flip();
        
        // Orphan then upload to avoid stalls
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) data.length * 4L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, buffer);
    }
    
    /**
     * Updates an existing int buffer with new data.
     */
    private void updateIntBuffer(int vboId, int[] data) {
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vboId);
        
        if (reusableIndexBuffer == null || reusableIndexBuffer.capacity() < data.length) {
            freeReusableBuffer(reusableIndexBuffer);
            reusableIndexBuffer = MemoryUtil.memAllocInt(data.length);
        }
        
        reusableIndexBuffer.clear();
        reusableIndexBuffer.put(data).flip();
        
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, (long) data.length * 4L, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, reusableIndexBuffer);
    }
    
    /**
     * Gets or creates a reusable float buffer of the required size.
     */
    private FloatBuffer getOrCreateFloatBuffer(int requiredSize) {
        // This is a simplified version - in practice you'd want separate buffers for each type
        if (reusableVertexBuffer == null || reusableVertexBuffer.capacity() < requiredSize) {
            freeReusableBuffer(reusableVertexBuffer);
            reusableVertexBuffer = MemoryUtil.memAllocFloat(requiredSize);
        }
        return reusableVertexBuffer;
    }
    
    /**
     * Safely deletes a buffer, handling invalid IDs gracefully.
     */
    private void safeDeleteBuffer(int bufferId) {
        if (bufferId > 0) {
            try {
                GL15.glDeleteBuffers(bufferId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error deleting buffer " + bufferId, e);
            }
        }
    }
    
    /**
     * Safely deletes a vertex array, handling invalid IDs gracefully.
     */
    private void safeDeleteVertexArray(int vaoId) {
        if (vaoId > 0) {
            try {
                GL30.glDeleteVertexArrays(vaoId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error deleting VAO " + vaoId, e);
            }
        }
    }
    
    /**
     * Safely frees a reusable buffer.
     */
    private void freeReusableBuffer(FloatBuffer buffer) {
        if (buffer != null) {
            try {
                MemoryUtil.memFree(buffer);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error freeing FloatBuffer", e);
            }
        }
    }
    
    /**
     * Safely frees a reusable buffer.
     */
    private void freeReusableBuffer(IntBuffer buffer) {
        if (buffer != null) {
            try {
                MemoryUtil.memFree(buffer);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error freeing IntBuffer", e);
            }
        }
    }
}
