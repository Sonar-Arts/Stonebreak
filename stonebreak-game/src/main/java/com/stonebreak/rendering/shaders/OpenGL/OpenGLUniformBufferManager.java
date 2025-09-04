package com.stonebreak.rendering.shaders.OpenGL;

import com.stonebreak.rendering.shaders.managers.IUniformBufferManager;
import com.stonebreak.rendering.shaders.exceptions.UniformBufferException;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;

/**
 * OpenGL implementation of uniform buffer manager.
 * Provides high-performance uniform data management using UBOs.
 */
public class OpenGLUniformBufferManager implements IUniformBufferManager {
    
    private static final Logger LOGGER = Logger.getLogger(OpenGLUniformBufferManager.class.getName());
    
    private final ConcurrentMap<String, UniformBufferInfo> uniformBuffers;
    private final AtomicLong totalBytesAllocated;
    private final int maxUniformBufferSize;
    private final int maxUniformBufferBindings;
    
    public OpenGLUniformBufferManager() {
        this.uniformBuffers = new ConcurrentHashMap<>();
        this.totalBytesAllocated = new AtomicLong(0);
        this.maxUniformBufferSize = glGetInteger(GL_MAX_UNIFORM_BLOCK_SIZE);
        this.maxUniformBufferBindings = glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS);
        
        LOGGER.log(Level.INFO, 
            "Initialized UniformBufferManager - Max size: {0} bytes, Max bindings: {1}",
            new Object[]{maxUniformBufferSize, maxUniformBufferBindings});
    }
    
    @Override
    public int createUniformBuffer(String name, int sizeInBytes, int usage) throws UniformBufferException {
        if (name == null || name.trim().isEmpty()) {
            throw new UniformBufferException("Buffer name cannot be null or empty", name);
        }
        
        if (sizeInBytes <= 0) {
            throw new UniformBufferException("Buffer size must be positive, got: " + sizeInBytes, name);
        }
        
        if (sizeInBytes > maxUniformBufferSize) {
            throw new UniformBufferException(
                String.format("Buffer size %d exceeds maximum %d", sizeInBytes, maxUniformBufferSize),
                name
            );
        }
        
        String trimmedName = name.trim();
        
        // Check if buffer already exists
        if (uniformBuffers.containsKey(trimmedName)) {
            throw new UniformBufferException("Buffer '" + trimmedName + "' already exists", trimmedName);
        }
        
        // Create OpenGL buffer
        int bufferId = glGenBuffers();
        if (bufferId == 0) {
            throw new UniformBufferException("Failed to generate OpenGL buffer", trimmedName);
        }
        
        try {
            // Initialize buffer
            glBindBuffer(GL_UNIFORM_BUFFER, bufferId);
            glBufferData(GL_UNIFORM_BUFFER, sizeInBytes, usage);
            glBindBuffer(GL_UNIFORM_BUFFER, 0);
            
            // Store buffer info
            UniformBufferInfo info = new UniformBufferInfo(bufferId, sizeInBytes, usage, System.currentTimeMillis());
            uniformBuffers.put(trimmedName, info);
            totalBytesAllocated.addAndGet(sizeInBytes);
            
            LOGGER.log(Level.FINE, "Created uniform buffer '{0}' (ID: {1}, size: {2} bytes)", 
                      new Object[]{trimmedName, bufferId, sizeInBytes});
            
            return bufferId;
            
        } catch (Exception e) {
            // Clean up on failure
            glDeleteBuffers(bufferId);
            throw new UniformBufferException("Failed to initialize uniform buffer: " + e.getMessage(), trimmedName, e);
        }
    }
    
    @Override
    public void bindUniformBuffer(String name, int bindingPoint) throws UniformBufferException {
        UniformBufferInfo info = getValidBufferInfo(name);
        
        if (bindingPoint < 0 || bindingPoint >= maxUniformBufferBindings) {
            throw new UniformBufferException(
                String.format("Invalid binding point %d (max: %d)", bindingPoint, maxUniformBufferBindings - 1),
                name
            );
        }
        
        glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, info.bufferId);
        
        LOGGER.log(Level.FINEST, "Bound uniform buffer '{0}' to binding point {1}", 
                  new Object[]{name, bindingPoint});
    }
    
    @Override
    public void bindUniformBlock(int programId, String uniformBlockName, int bindingPoint) throws UniformBufferException {
        if (uniformBlockName == null || uniformBlockName.trim().isEmpty()) {
            throw new UniformBufferException("Uniform block name cannot be null or empty", uniformBlockName);
        }
        
        if (bindingPoint < 0 || bindingPoint >= maxUniformBufferBindings) {
            throw new UniformBufferException(
                String.format("Invalid binding point %d (max: %d)", bindingPoint, maxUniformBufferBindings - 1),
                uniformBlockName
            );
        }
        
        int blockIndex = glGetUniformBlockIndex(programId, uniformBlockName.trim());
        if (blockIndex == GL_INVALID_INDEX) {
            throw new UniformBufferException(
                "Uniform block '" + uniformBlockName + "' not found in program " + programId,
                uniformBlockName
            );
        }
        
        glUniformBlockBinding(programId, blockIndex, bindingPoint);
        
        LOGGER.log(Level.FINE, "Bound uniform block '{0}' (program {1}) to binding point {2}", 
                  new Object[]{uniformBlockName, programId, bindingPoint});
    }
    
    @Override
    public void updateUniformBuffer(String name, ByteBuffer data) throws UniformBufferException {
        UniformBufferInfo info = getValidBufferInfo(name);
        
        if (data == null) {
            throw new UniformBufferException("Data buffer cannot be null", name);
        }
        
        if (data.remaining() > info.sizeInBytes) {
            throw new UniformBufferException(
                String.format("Data size %d exceeds buffer size %d", data.remaining(), info.sizeInBytes),
                name
            );
        }
        
        glBindBuffer(GL_UNIFORM_BUFFER, info.bufferId);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, data);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        
        LOGGER.log(Level.FINEST, "Updated uniform buffer '{0}' with {1} bytes", 
                  new Object[]{name, data.remaining()});
    }
    
    @Override
    public void updateUniformBufferSubData(String name, int offset, ByteBuffer data) throws UniformBufferException {
        UniformBufferInfo info = getValidBufferInfo(name);
        
        if (data == null) {
            throw new UniformBufferException("Data buffer cannot be null", name);
        }
        
        if (offset < 0) {
            throw new UniformBufferException("Offset cannot be negative: " + offset, name);
        }
        
        if (offset + data.remaining() > info.sizeInBytes) {
            throw new UniformBufferException(
                String.format("Update would exceed buffer bounds (offset: %d, size: %d, buffer: %d)",
                    offset, data.remaining(), info.sizeInBytes),
                name
            );
        }
        
        glBindBuffer(GL_UNIFORM_BUFFER, info.bufferId);
        glBufferSubData(GL_UNIFORM_BUFFER, offset, data);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        
        LOGGER.log(Level.FINEST, "Updated uniform buffer '{0}' at offset {1} with {2} bytes", 
                  new Object[]{name, offset, data.remaining()});
    }
    
    @Override
    public ByteBuffer mapUniformBuffer(String name, int access) throws UniformBufferException {
        UniformBufferInfo info = getValidBufferInfo(name);
        
        glBindBuffer(GL_UNIFORM_BUFFER, info.bufferId);
        ByteBuffer mapped = glMapBuffer(GL_UNIFORM_BUFFER, access);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        
        if (mapped == null) {
            throw new UniformBufferException("Failed to map uniform buffer", name);
        }
        
        info.isMapped = true;
        
        LOGGER.log(Level.FINEST, "Mapped uniform buffer '{0}' for {1} access", 
                  new Object[]{name, getAccessModeName(access)});
        
        return mapped;
    }
    
    @Override
    public boolean unmapUniformBuffer(String name) throws UniformBufferException {
        UniformBufferInfo info = getValidBufferInfo(name);
        
        if (!info.isMapped) {
            throw new UniformBufferException("Buffer '" + name + "' is not currently mapped", name);
        }
        
        glBindBuffer(GL_UNIFORM_BUFFER, info.bufferId);
        boolean result = glUnmapBuffer(GL_UNIFORM_BUFFER);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        
        info.isMapped = false;
        
        LOGGER.log(Level.FINEST, "Unmapped uniform buffer '{0}' - success: {1}", 
                  new Object[]{name, result});
        
        return result;
    }
    
    @Override
    public int getUniformBufferSize(String name) {
        if (name == null) {
            return -1;
        }
        UniformBufferInfo info = uniformBuffers.get(name.trim());
        return info != null ? info.sizeInBytes : -1;
    }
    
    @Override
    public int getUniformBufferId(String name) {
        if (name == null) {
            return 0;
        }
        UniformBufferInfo info = uniformBuffers.get(name.trim());
        return info != null ? info.bufferId : 0;
    }
    
    @Override
    public boolean hasUniformBuffer(String name) {
        if (name == null) {
            return false;
        }
        return uniformBuffers.containsKey(name.trim());
    }
    
    @Override
    public boolean deleteUniformBuffer(String name) {
        if (name == null) {
            return false;
        }
        
        String trimmedName = name.trim();
        UniformBufferInfo info = uniformBuffers.remove(trimmedName);
        
        if (info != null) {
            glDeleteBuffers(info.bufferId);
            totalBytesAllocated.addAndGet(-info.sizeInBytes);
            
            long lifespan = System.currentTimeMillis() - info.creationTime;
            LOGGER.log(Level.FINE, "Deleted uniform buffer '{0}' (ID: {1}, lifespan: {2}ms)", 
                      new Object[]{trimmedName, info.bufferId, lifespan});
            return true;
        }
        
        return false;
    }
    
    @Override
    public int getMaxUniformBufferSize() {
        return maxUniformBufferSize;
    }
    
    @Override
    public int getMaxUniformBufferBindings() {
        return maxUniformBufferBindings;
    }
    
    @Override
    public int getAllocatedBufferCount() {
        return uniformBuffers.size();
    }
    
    @Override
    public void cleanupAll() {
        int bufferCount = uniformBuffers.size();
        long bytesFreed = totalBytesAllocated.get();
        
        uniformBuffers.forEach((name, info) -> {
            glDeleteBuffers(info.bufferId);
            LOGGER.log(Level.FINE, "Cleaned up uniform buffer '{0}' (ID: {1})", 
                      new Object[]{name, info.bufferId});
        });
        
        uniformBuffers.clear();
        totalBytesAllocated.set(0);
        
        if (bufferCount > 0) {
            LOGGER.log(Level.INFO, "Cleaned up {0} uniform buffers ({1} bytes freed)", 
                      new Object[]{bufferCount, bytesFreed});
        }
    }
    
    @Override
    public String getUsageSummary() {
        long currentTime = System.currentTimeMillis();
        StringBuilder summary = new StringBuilder();
        summary.append("=== Uniform Buffer Usage Summary ===\n");
        summary.append(String.format("Max Buffer Size: %d bytes\n", maxUniformBufferSize));
        summary.append(String.format("Max Bindings: %d\n", maxUniformBufferBindings));
        summary.append(String.format("Allocated Buffers: %d\n", uniformBuffers.size()));
        summary.append(String.format("Total Allocated: %d bytes\n", totalBytesAllocated.get()));
        
        if (!uniformBuffers.isEmpty()) {
            summary.append("\nAllocated Buffers:\n");
            uniformBuffers.forEach((name, info) -> {
                long age = currentTime - info.creationTime;
                summary.append(String.format("  '%s' - ID: %d, Size: %d bytes, Age: %dms%s\n", 
                    name, info.bufferId, info.sizeInBytes, age, info.isMapped ? " (MAPPED)" : ""));
            });
        }
        
        summary.append("======================================");
        return summary.toString();
    }
    
    private UniformBufferInfo getValidBufferInfo(String name) throws UniformBufferException {
        if (name == null || name.trim().isEmpty()) {
            throw new UniformBufferException("Buffer name cannot be null or empty", name);
        }
        
        String trimmedName = name.trim();
        UniformBufferInfo info = uniformBuffers.get(trimmedName);
        
        if (info == null) {
            throw new UniformBufferException("Uniform buffer '" + trimmedName + "' not found", trimmedName);
        }
        
        return info;
    }
    
    private String getAccessModeName(int access) {
        switch (access) {
            case GL_READ_ONLY:
                return "READ_ONLY";
            case GL_WRITE_ONLY:
                return "WRITE_ONLY";
            case GL_READ_WRITE:
                return "READ_WRITE";
            default:
                return "UNKNOWN(" + access + ")";
        }
    }
    
    private static class UniformBufferInfo {
        final int bufferId;
        final int sizeInBytes;
        final int usage;
        final long creationTime;
        volatile boolean isMapped;
        
        UniformBufferInfo(int bufferId, int sizeInBytes, int usage, long creationTime) {
            this.bufferId = bufferId;
            this.sizeInBytes = sizeInBytes;
            this.usage = usage;
            this.creationTime = creationTime;
            this.isMapped = false;
        }
    }
}