package com.stonebreak.rendering.shaders.managers;

/**
 * Interface for managing shader resource lifecycle.
 * Handles proper cleanup and resource tracking to prevent memory leaks.
 */
public interface IShaderResourceManager {
    
    /**
     * Registers a shader for lifecycle management.
     * @param shaderId The shader ID to track
     * @param shaderType The OpenGL shader type
     */
    void registerShader(int shaderId, int shaderType);
    
    /**
     * Registers a shader program for lifecycle management.
     * @param programId The program ID to track
     */
    void registerProgram(int programId);
    
    /**
     * Unregisters and deletes a shader.
     * @param shaderId The shader ID to delete
     * @return true if shader was found and deleted
     */
    boolean deleteShader(int shaderId);
    
    /**
     * Unregisters and deletes a shader program.
     * @param programId The program ID to delete
     * @return true if program was found and deleted
     */
    boolean deleteProgram(int programId);
    
    /**
     * Checks if a shader is currently registered.
     * @param shaderId The shader ID
     * @return true if registered
     */
    boolean isShaderRegistered(int shaderId);
    
    /**
     * Checks if a program is currently registered.
     * @param programId The program ID
     * @return true if registered
     */
    boolean isProgramRegistered(int programId);
    
    /**
     * Gets the number of tracked shaders.
     * @return The count of tracked shaders
     */
    int getTrackedShaderCount();
    
    /**
     * Gets the number of tracked programs.
     * @return The count of tracked programs
     */
    int getTrackedProgramCount();
    
    /**
     * Cleans up all tracked resources.
     * Should be called during shutdown to prevent memory leaks.
     */
    void cleanupAll();
    
    /**
     * Gets a summary of tracked resources for debugging.
     * @return String containing resource summary
     */
    String getResourceSummary();
}