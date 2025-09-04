package com.stonebreak.rendering.shaders.managers;

import com.stonebreak.rendering.shaders.exceptions.UniformException;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Interface for managing shader uniforms.
 * Provides type-safe uniform operations with null safety.
 */
public interface IUniformManager {
    
    /**
     * Registers a uniform location for the given name.
     * @param uniformName The name of the uniform
     * @param programId The shader program ID
     * @return true if uniform was found and registered, false otherwise
     */
    boolean registerUniform(String uniformName, int programId);
    
    /**
     * Checks if a uniform is registered.
     * @param uniformName The uniform name
     * @return true if uniform is registered
     */
    boolean hasUniform(String uniformName);
    
    /**
     * Gets the location of a uniform.
     * @param uniformName The uniform name
     * @return The uniform location, or -1 if not found
     */
    int getUniformLocation(String uniformName);
    
    // Type-safe uniform setters
    void setUniform(String uniformName, int value) throws UniformException;
    void setUniform(String uniformName, float value) throws UniformException;
    void setUniform(String uniformName, boolean value) throws UniformException;
    void setUniform(String uniformName, Vector2f value) throws UniformException;
    void setUniform(String uniformName, Vector3f value) throws UniformException;
    void setUniform(String uniformName, Vector4f value) throws UniformException;
    void setUniform(String uniformName, Matrix4f value) throws UniformException;
    
    // Array setters
    void setUniform(String uniformName, int[] values) throws UniformException;
    void setUniform(String uniformName, float[] values) throws UniformException;
    
    /**
     * Gets the current value of a matrix uniform.
     * @param uniformName The uniform name
     * @param buffer The buffer to store the matrix values (must be size 16)
     * @throws UniformException if uniform not found or buffer wrong size
     */
    void getUniformMatrix4fv(String uniformName, float[] buffer) throws UniformException;
    
    /**
     * Clears all registered uniforms.
     */
    void clear();
    
    /**
     * Gets the number of registered uniforms.
     * @return The count of registered uniforms
     */
    int getUniformCount();
}