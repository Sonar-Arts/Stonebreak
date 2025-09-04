package com.stonebreak.rendering.shaders.managers;

import com.stonebreak.rendering.shaders.exceptions.UniformException;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL20.*;

/**
 * Thread-safe implementation of uniform manager.
 * Uses ConcurrentHashMap for thread-safe uniform location caching.
 */
public class ThreadSafeUniformManager implements IUniformManager {
    
    private static final Logger LOGGER = Logger.getLogger(ThreadSafeUniformManager.class.getName());
    
    private final ConcurrentMap<String, Integer> uniformLocations;
    private final int programId;
    
    public ThreadSafeUniformManager(int programId) {
        this.programId = programId;
        this.uniformLocations = new ConcurrentHashMap<>();
    }
    
    @Override
    public boolean registerUniform(String uniformName, int programId) {
        if (uniformName == null || uniformName.trim().isEmpty()) {
            LOGGER.log(Level.WARNING, "Attempted to register null or empty uniform name");
            return false;
        }
        
        int location = glGetUniformLocation(programId, uniformName.trim());
        if (location < 0) {
            LOGGER.log(Level.WARNING, "Could not find uniform: {0}", uniformName);
            return false;
        }
        
        uniformLocations.put(uniformName.trim(), location);
        LOGGER.log(Level.FINE, "Registered uniform '{0}' at location {1}", 
                  new Object[]{uniformName, location});
        return true;
    }
    
    @Override
    public boolean hasUniform(String uniformName) {
        if (uniformName == null) {
            return false;
        }
        return uniformLocations.containsKey(uniformName.trim());
    }
    
    @Override
    public int getUniformLocation(String uniformName) {
        if (uniformName == null) {
            return -1;
        }
        return uniformLocations.getOrDefault(uniformName.trim(), -1);
    }
    
    @Override
    public void setUniform(String uniformName, int value) throws UniformException {
        Integer location = getValidUniformLocation(uniformName);
        glUniform1i(location, value);
    }
    
    @Override
    public void setUniform(String uniformName, float value) throws UniformException {
        Integer location = getValidUniformLocation(uniformName);
        glUniform1f(location, value);
    }
    
    @Override
    public void setUniform(String uniformName, boolean value) throws UniformException {
        Integer location = getValidUniformLocation(uniformName);
        glUniform1i(location, value ? 1 : 0);
    }
    
    @Override
    public void setUniform(String uniformName, Vector2f value) throws UniformException {
        if (value == null) {
            throw new UniformException("Vector2f value cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        glUniform2f(location, value.x, value.y);
    }
    
    @Override
    public void setUniform(String uniformName, Vector3f value) throws UniformException {
        if (value == null) {
            throw new UniformException("Vector3f value cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        glUniform3f(location, value.x, value.y, value.z);
    }
    
    @Override
    public void setUniform(String uniformName, Vector4f value) throws UniformException {
        if (value == null) {
            throw new UniformException("Vector4f value cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        glUniform4f(location, value.x, value.y, value.z, value.w);
    }
    
    @Override
    public void setUniform(String uniformName, Matrix4f value) throws UniformException {
        if (value == null) {
            throw new UniformException("Matrix4f value cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            value.get(buffer);
            glUniformMatrix4fv(location, false, buffer);
        }
    }
    
    @Override
    public void setUniform(String uniformName, int[] values) throws UniformException {
        if (values == null) {
            throw new UniformException("int[] values cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        glUniform1iv(location, values);
    }
    
    @Override
    public void setUniform(String uniformName, float[] values) throws UniformException {
        if (values == null) {
            throw new UniformException("float[] values cannot be null", uniformName);
        }
        Integer location = getValidUniformLocation(uniformName);
        glUniform1fv(location, values);
    }
    
    @Override
    public void getUniformMatrix4fv(String uniformName, float[] buffer) throws UniformException {
        if (buffer == null) {
            throw new UniformException("Buffer cannot be null", uniformName);
        }
        if (buffer.length != 16) {
            throw new UniformException("Buffer must be of size 16, got " + buffer.length, uniformName);
        }
        
        Integer location = getValidUniformLocation(uniformName);
        glGetUniformfv(programId, location, buffer);
    }
    
    @Override
    public void clear() {
        uniformLocations.clear();
        LOGGER.log(Level.FINE, "Cleared all uniform locations");
    }
    
    @Override
    public int getUniformCount() {
        return uniformLocations.size();
    }
    
    private Integer getValidUniformLocation(String uniformName) throws UniformException {
        if (uniformName == null || uniformName.trim().isEmpty()) {
            throw new UniformException("Uniform name cannot be null or empty", uniformName);
        }
        
        String trimmedName = uniformName.trim();
        Integer location = uniformLocations.get(trimmedName);
        
        if (location == null) {
            throw new UniformException("Uniform '" + trimmedName + "' not registered. Call registerUniform() first.", trimmedName);
        }
        
        return location;
    }
}