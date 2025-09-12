package com.stonebreak.rendering.shaders.OpenGL;

import com.stonebreak.rendering.shaders.managers.IShaderResourceManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER;

/**
 * OpenGL implementation of shader resource manager.
 * Tracks shader and program resources to prevent memory leaks.
 */
public class OpenGLShaderResourceManager implements IShaderResourceManager {
    
    private static final Logger LOGGER = Logger.getLogger(OpenGLShaderResourceManager.class.getName());
    
    private final ConcurrentMap<Integer, ShaderResource> trackedShaders;
    private final ConcurrentMap<Integer, ProgramResource> trackedPrograms;
    private final AtomicLong totalShadersCreated;
    private final AtomicLong totalProgramsCreated;
    
    public OpenGLShaderResourceManager() {
        this.trackedShaders = new ConcurrentHashMap<>();
        this.trackedPrograms = new ConcurrentHashMap<>();
        this.totalShadersCreated = new AtomicLong(0);
        this.totalProgramsCreated = new AtomicLong(0);
    }
    
    @Override
    public void registerShader(int shaderId, int shaderType) {
        if (shaderId <= 0) {
            LOGGER.log(Level.WARNING, "Attempted to register invalid shader ID: {0}", shaderId);
            return;
        }
        
        ShaderResource resource = new ShaderResource(shaderId, shaderType, System.currentTimeMillis());
        trackedShaders.put(shaderId, resource);
        totalShadersCreated.incrementAndGet();
        
        LOGGER.log(Level.FINE, "Registered {0} shader (ID: {1})", 
                  new Object[]{getShaderTypeName(shaderType), shaderId});
    }
    
    @Override
    public void registerProgram(int programId) {
        if (programId <= 0) {
            LOGGER.log(Level.WARNING, "Attempted to register invalid program ID: {0}", programId);
            return;
        }
        
        ProgramResource resource = new ProgramResource(programId, System.currentTimeMillis());
        trackedPrograms.put(programId, resource);
        totalProgramsCreated.incrementAndGet();
        
        LOGGER.log(Level.FINE, "Registered shader program (ID: {0})", programId);
    }
    
    @Override
    public boolean deleteShader(int shaderId) {
        if (shaderId <= 0) {
            return false;
        }
        
        ShaderResource resource = trackedShaders.remove(shaderId);
        if (resource != null) {
            glDeleteShader(shaderId);
            long lifespan = System.currentTimeMillis() - resource.creationTime;
            LOGGER.log(Level.FINE, "Deleted {0} shader (ID: {1}, lifespan: {2}ms)", 
                      new Object[]{getShaderTypeName(resource.shaderType), shaderId, lifespan});
            return true;
        }
        
        return false;
    }
    
    @Override
    public boolean deleteProgram(int programId) {
        if (programId <= 0) {
            return false;
        }
        
        ProgramResource resource = trackedPrograms.remove(programId);
        if (resource != null) {
            glDeleteProgram(programId);
            long lifespan = System.currentTimeMillis() - resource.creationTime;
            LOGGER.log(Level.FINE, "Deleted shader program (ID: {0}, lifespan: {1}ms)", 
                      new Object[]{programId, lifespan});
            return true;
        }
        
        return false;
    }
    
    @Override
    public boolean isShaderRegistered(int shaderId) {
        return trackedShaders.containsKey(shaderId);
    }
    
    @Override
    public boolean isProgramRegistered(int programId) {
        return trackedPrograms.containsKey(programId);
    }
    
    @Override
    public int getTrackedShaderCount() {
        return trackedShaders.size();
    }
    
    @Override
    public int getTrackedProgramCount() {
        return trackedPrograms.size();
    }
    
    @Override
    public void cleanupAll() {
        int shaderCount = trackedShaders.size();
        int programCount = trackedPrograms.size();
        
        // Delete all tracked shaders
        trackedShaders.keySet().forEach(shaderId -> {
            glDeleteShader(shaderId);
            LOGGER.log(Level.FINE, "Cleaned up shader (ID: {0})", shaderId);
        });
        trackedShaders.clear();
        
        // Delete all tracked programs
        trackedPrograms.keySet().forEach(programId -> {
            glDeleteProgram(programId);
            LOGGER.log(Level.FINE, "Cleaned up program (ID: {0})", programId);
        });
        trackedPrograms.clear();
        
        if (shaderCount > 0 || programCount > 0) {
            LOGGER.log(Level.INFO, "Cleaned up {0} shaders and {1} programs", 
                      new Object[]{shaderCount, programCount});
        }
    }
    
    @Override
    public String getResourceSummary() {
        long currentTime = System.currentTimeMillis();
        StringBuilder summary = new StringBuilder();
        summary.append("=== Shader Resource Summary ===\n");
        summary.append(String.format("Active Shaders: %d\n", trackedShaders.size()));
        summary.append(String.format("Active Programs: %d\n", trackedPrograms.size()));
        summary.append(String.format("Total Shaders Created: %d\n", totalShadersCreated.get()));
        summary.append(String.format("Total Programs Created: %d\n", totalProgramsCreated.get()));
        
        if (!trackedShaders.isEmpty()) {
            summary.append("\nActive Shaders:\n");
            trackedShaders.forEach((id, resource) -> {
                long age = currentTime - resource.creationTime;
                summary.append(String.format("  ID %d (%s) - age: %dms\n", 
                    id, getShaderTypeName(resource.shaderType), age));
            });
        }
        
        if (!trackedPrograms.isEmpty()) {
            summary.append("\nActive Programs:\n");
            trackedPrograms.forEach((id, resource) -> {
                long age = currentTime - resource.creationTime;
                summary.append(String.format("  ID %d - age: %dms\n", id, age));
            });
        }
        
        summary.append("===============================");
        return summary.toString();
    }
    
    private String getShaderTypeName(int shaderType) {
        switch (shaderType) {
            case GL_VERTEX_SHADER:
                return "Vertex";
            case GL_FRAGMENT_SHADER:
                return "Fragment";
            case GL_GEOMETRY_SHADER:
                return "Geometry";
            default:
                return "Unknown(" + shaderType + ")";
        }
    }
    
    private static class ShaderResource {
        final int shaderId;
        final int shaderType;
        final long creationTime;
        
        ShaderResource(int shaderId, int shaderType, long creationTime) {
            this.shaderId = shaderId;
            this.shaderType = shaderType;
            this.creationTime = creationTime;
        }
    }
    
    private static class ProgramResource {
        final int programId;
        final long creationTime;
        
        ProgramResource(int programId, long creationTime) {
            this.programId = programId;
            this.creationTime = creationTime;
        }
    }
}