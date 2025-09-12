package com.stonebreak.rendering.shaders.managers;

import com.stonebreak.rendering.shaders.exceptions.UniformBufferException;

import java.nio.ByteBuffer;

/**
 * Interface for managing Uniform Buffer Objects (UBOs).
 * UBOs provide high-performance uniform data management for complex shaders.
 */
public interface IUniformBufferManager {
    
    /**
     * Creates a new uniform buffer with the specified size.
     * @param name The name/identifier for the buffer
     * @param sizeInBytes The size of the buffer in bytes
     * @param usage The buffer usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     * @return The OpenGL buffer ID
     * @throws UniformBufferException if creation fails
     */
    int createUniformBuffer(String name, int sizeInBytes, int usage) throws UniformBufferException;
    
    /**
     * Binds a uniform buffer to a specific binding point.
     * @param name The buffer name
     * @param bindingPoint The binding point index
     * @throws UniformBufferException if buffer not found or binding fails
     */
    void bindUniformBuffer(String name, int bindingPoint) throws UniformBufferException;
    
    /**
     * Binds a uniform block in a shader program to a binding point.
     * @param programId The shader program ID
     * @param uniformBlockName The name of the uniform block in the shader
     * @param bindingPoint The binding point to bind to
     * @throws UniformBufferException if uniform block not found
     */
    void bindUniformBlock(int programId, String uniformBlockName, int bindingPoint) throws UniformBufferException;
    
    /**
     * Updates the entire uniform buffer with new data.
     * @param name The buffer name
     * @param data The data to upload
     * @throws UniformBufferException if buffer not found or update fails
     */
    void updateUniformBuffer(String name, ByteBuffer data) throws UniformBufferException;
    
    /**
     * Updates a portion of the uniform buffer with new data.
     * @param name The buffer name
     * @param offset The offset in bytes where to start updating
     * @param data The data to upload
     * @throws UniformBufferException if buffer not found or update fails
     */
    void updateUniformBufferSubData(String name, int offset, ByteBuffer data) throws UniformBufferException;
    
    /**
     * Maps a uniform buffer for direct memory access.
     * @param name The buffer name
     * @param access The access mode (GL_READ_ONLY, GL_WRITE_ONLY, GL_READ_WRITE)
     * @return ByteBuffer for direct access, or null if mapping fails
     * @throws UniformBufferException if buffer not found
     */
    ByteBuffer mapUniformBuffer(String name, int access) throws UniformBufferException;
    
    /**
     * Unmaps a previously mapped uniform buffer.
     * @param name The buffer name
     * @return true if unmapping succeeded
     * @throws UniformBufferException if buffer not found or not mapped
     */
    boolean unmapUniformBuffer(String name) throws UniformBufferException;
    
    /**
     * Gets the size of a uniform buffer in bytes.
     * @param name The buffer name
     * @return The size in bytes, or -1 if not found
     */
    int getUniformBufferSize(String name);
    
    /**
     * Gets the OpenGL buffer ID for a uniform buffer.
     * @param name The buffer name
     * @return The buffer ID, or 0 if not found
     */
    int getUniformBufferId(String name);
    
    /**
     * Checks if a uniform buffer exists.
     * @param name The buffer name
     * @return true if buffer exists
     */
    boolean hasUniformBuffer(String name);
    
    /**
     * Deletes a uniform buffer and releases its resources.
     * @param name The buffer name
     * @return true if buffer was found and deleted
     */
    boolean deleteUniformBuffer(String name);
    
    /**
     * Gets the maximum uniform buffer size supported.
     * @return Maximum size in bytes
     */
    int getMaxUniformBufferSize();
    
    /**
     * Gets the maximum number of uniform buffer binding points.
     * @return Maximum binding points
     */
    int getMaxUniformBufferBindings();
    
    /**
     * Gets the number of currently allocated uniform buffers.
     * @return The count of allocated buffers
     */
    int getAllocatedBufferCount();
    
    /**
     * Cleans up all uniform buffers.
     */
    void cleanupAll();
    
    /**
     * Gets a summary of uniform buffer usage.
     * @return String containing usage summary
     */
    String getUsageSummary();
}