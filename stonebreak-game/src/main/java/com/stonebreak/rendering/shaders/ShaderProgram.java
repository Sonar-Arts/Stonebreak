package com.stonebreak.rendering.shaders;

import com.stonebreak.rendering.shaders.OpenGL.OpenGLShaderCompiler;
import com.stonebreak.rendering.shaders.OpenGL.OpenGLShaderResourceManager;
import com.stonebreak.rendering.shaders.OpenGL.OpenGLTextureUnitManager;
import com.stonebreak.rendering.shaders.OpenGL.OpenGLUniformBufferManager;
import com.stonebreak.rendering.shaders.exceptions.*;
import com.stonebreak.rendering.shaders.managers.*;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL20.*;

/**
 * Refactored shader program that coordinates specialized components.
 * Follows composition over inheritance and SOLID principles.
 * 
 * This class maintains backward compatibility while providing enhanced
 * functionality through modular components.
 */
public class ShaderProgram {
    
    private static final Logger LOGGER = Logger.getLogger(ShaderProgram.class.getName());
    
    private final int programId;
    private final IShaderCompiler shaderCompiler;
    private final IUniformManager uniformManager;
    private final IShaderResourceManager resourceManager;
    private final ITextureUnitManager textureUnitManager;
    private final IUniformBufferManager uniformBufferManager;
    private final GeometryShaderSupport geometryShaderSupport;
    
    private int vertexShaderId;
    private int fragmentShaderId;
    private int geometryShaderId;
    private boolean isLinked = false;
    
    /**
     * Creates a new shader program with default components.
     */
    public ShaderProgram() {
        this(new OpenGLShaderCompiler(false));
    }
    
    /**
     * Creates a new shader program with debug mode.
     */
    public ShaderProgram(boolean debugMode) {
        this(new OpenGLShaderCompiler(debugMode));
    }
    
    /**
     * Creates a new shader program with custom shader compiler.
     */
    public ShaderProgram(IShaderCompiler shaderCompiler) {
        this.programId = glCreateProgram();
        if (programId == 0) {
            throw new RuntimeException("Could not create shader program");
        }
        
        this.shaderCompiler = shaderCompiler;
        this.resourceManager = new OpenGLShaderResourceManager();
        this.uniformManager = new ThreadSafeUniformManager(programId);
        this.textureUnitManager = new OpenGLTextureUnitManager();
        this.uniformBufferManager = new OpenGLUniformBufferManager();
        this.geometryShaderSupport = new GeometryShaderSupport(shaderCompiler, resourceManager);
        
        // Register the program for resource management
        resourceManager.registerProgram(programId);
        
        LOGGER.log(Level.FINE, "Created shader program (ID: {0})", programId);
    }
    
    /**
     * Creates a vertex shader from source code.
     */
    public void createVertexShader(String shaderCode) {
        try {
            vertexShaderId = shaderCompiler.compileShader(shaderCode, GL_VERTEX_SHADER);
            resourceManager.registerShader(vertexShaderId, GL_VERTEX_SHADER);
            glAttachShader(programId, vertexShaderId);
        } catch (ShaderCompilationException e) {
            LOGGER.log(Level.SEVERE, "Failed to create vertex shader: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create vertex shader", e);
        }
    }
    
    /**
     * Creates a fragment shader from source code.
     */
    public void createFragmentShader(String shaderCode) {
        try {
            fragmentShaderId = shaderCompiler.compileShader(shaderCode, GL_FRAGMENT_SHADER);
            resourceManager.registerShader(fragmentShaderId, GL_FRAGMENT_SHADER);
            glAttachShader(programId, fragmentShaderId);
        } catch (ShaderCompilationException e) {
            LOGGER.log(Level.SEVERE, "Failed to create fragment shader: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create fragment shader", e);
        }
    }
    
    /**
     * Creates a geometry shader from source code.
     */
    public void createGeometryShader(String shaderCode) {
        try {
            geometryShaderId = geometryShaderSupport.createGeometryShader(shaderCode);
            glAttachShader(programId, geometryShaderId);
        } catch (ShaderCompilationException e) {
            LOGGER.log(Level.SEVERE, "Failed to create geometry shader: " + e.getMessage(), e);
            throw new RuntimeException("Failed to create geometry shader", e);
        }
    }
    
    /**
     * Configures geometry shader parameters (must be called before linking).
     */
    public void configureGeometryShader(int inputType, int outputType, int maxOutputVertices) {
        geometryShaderSupport.configureGeometryShader(programId, inputType, outputType, maxOutputVertices);
    }
    
    /**
     * Links the shader program.
     */
    public void link() {
        if (isLinked) {
            LOGGER.log(Level.WARNING, "Shader program already linked (ID: {0})", programId);
            return;
        }
        
        try {
            // Collect shader IDs for linking
            int[] shaderIds = new int[3];
            int count = 0;
            
            if (vertexShaderId != 0) {
                shaderIds[count++] = vertexShaderId;
            }
            if (geometryShaderId != 0) {
                shaderIds[count++] = geometryShaderId;
            }
            if (fragmentShaderId != 0) {
                shaderIds[count++] = fragmentShaderId;
            }
            
            // Trim array to actual size
            int[] actualShaderIds = new int[count];
            System.arraycopy(shaderIds, 0, actualShaderIds, 0, count);
            
            // Link using the compiler
            shaderCompiler.linkProgram(programId, actualShaderIds);
            
            // Clean up individual shaders after linking
            if (vertexShaderId != 0) {
                resourceManager.deleteShader(vertexShaderId);
                vertexShaderId = 0;
            }
            if (geometryShaderId != 0) {
                resourceManager.deleteShader(geometryShaderId);
                geometryShaderId = 0;
            }
            if (fragmentShaderId != 0) {
                resourceManager.deleteShader(fragmentShaderId);
                fragmentShaderId = 0;
            }
            
            // Validate program
            if (!shaderCompiler.validateProgram(programId)) {
                LOGGER.log(Level.WARNING, "Shader program validation warning: {0}", 
                          shaderCompiler.getProgramInfoLog(programId));
            }
            
            isLinked = true;
            LOGGER.log(Level.FINE, "Successfully linked shader program (ID: {0})", programId);
            
        } catch (ShaderLinkException e) {
            LOGGER.log(Level.SEVERE, "Failed to link shader program: " + e.getMessage(), e);
            throw new RuntimeException("Failed to link shader program", e);
        }
    }
    
    /**
     * Binds the shader program.
     */
    public void bind() {
        if (!isLinked) {
            LOGGER.log(Level.WARNING, "Attempting to bind unlinked shader program (ID: {0})", programId);
        }
        glUseProgram(programId);
    }
    
    /**
     * Unbinds the shader program.
     */
    public void unbind() {
        glUseProgram(0);
    }
    
    /**
     * Checks if the shader program is currently linked.
     */
    public boolean isLinked() {
        return isLinked;
    }
    
    /**
     * Gets the program ID.
     */
    public int getProgramId() {
        return programId;
    }
    
    /**
     * Creates a uniform and registers it for management.
     */
    public void createUniform(String uniformName) {
        uniformManager.registerUniform(uniformName, programId);
    }
    
    /**
     * Checks if a uniform exists.
     */
    public boolean hasUniform(String uniformName) {
        return uniformManager.hasUniform(uniformName);
    }
    
    /**
     * Gets the uniform location.
     */
    public int getUniformLocation(String uniformName) {
        return uniformManager.getUniformLocation(uniformName);
    }
    
    // Type-safe uniform setters with backwards compatibility (RuntimeException on error)
    public void setUniform(String uniformName, int value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, float value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, boolean value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, Vector2f value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, Vector3f value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, Vector4f value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void setUniform(String uniformName, Matrix4f value) {
        try {
            uniformManager.setUniform(uniformName, value);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to set uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void getUniformMatrix4fv(String uniformName, float[] buffer) {
        try {
            uniformManager.getUniformMatrix4fv(uniformName, buffer);
        } catch (UniformException e) {
            LOGGER.log(Level.SEVERE, "Failed to get uniform: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    // Advanced features - texture unit management
    public int allocateTextureUnit(String name) {
        try {
            return textureUnitManager.allocateTextureUnit(name);
        } catch (TextureUnitException e) {
            LOGGER.log(Level.SEVERE, "Failed to allocate texture unit: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void bindTextureToUnit(String unitName, int textureId, int target) {
        try {
            textureUnitManager.bindTextureToUnit(unitName, textureId, target);
        } catch (TextureUnitException e) {
            LOGGER.log(Level.SEVERE, "Failed to bind texture to unit: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void releaseTextureUnit(String name) {
        textureUnitManager.releaseTextureUnit(name);
    }
    
    // Advanced features - Uniform Buffer Objects
    public int createUniformBuffer(String name, int sizeInBytes, int usage) {
        try {
            return uniformBufferManager.createUniformBuffer(name, sizeInBytes, usage);
        } catch (UniformBufferException e) {
            LOGGER.log(Level.SEVERE, "Failed to create uniform buffer: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void bindUniformBuffer(String name, int bindingPoint) {
        try {
            uniformBufferManager.bindUniformBuffer(name, bindingPoint);
        } catch (UniformBufferException e) {
            LOGGER.log(Level.SEVERE, "Failed to bind uniform buffer: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void bindUniformBlock(String uniformBlockName, int bindingPoint) {
        try {
            uniformBufferManager.bindUniformBlock(programId, uniformBlockName, bindingPoint);
        } catch (UniformBufferException e) {
            LOGGER.log(Level.SEVERE, "Failed to bind uniform block: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    public void updateUniformBuffer(String name, ByteBuffer data) {
        try {
            uniformBufferManager.updateUniformBuffer(name, data);
        } catch (UniformBufferException e) {
            LOGGER.log(Level.SEVERE, "Failed to update uniform buffer: " + e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
    
    // Geometry shader support
    public boolean supportsGeometryShaders() {
        return GeometryShaderSupport.isGeometryShaderSupported();
    }
    
    // Resource and debugging methods
    public String getResourceSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== Shader Program Resource Summary ===\n");
        summary.append(String.format("Program ID: %d\n", programId));
        summary.append(String.format("Linked: %s\n", isLinked));
        summary.append(String.format("Uniforms: %d\n", uniformManager.getUniformCount()));
        summary.append("\n").append(resourceManager.getResourceSummary());
        summary.append("\n").append(textureUnitManager.getUsageSummary());
        summary.append("\n").append(uniformBufferManager.getUsageSummary());
        summary.append("\n===========================================");
        return summary.toString();
    }
    
    /**
     * Cleans up all resources used by the shader program.
     */
    public void cleanup() {
        unbind();
        
        // Clean up texture units
        textureUnitManager.releaseAllUnits();
        
        // Clean up uniform buffers
        uniformBufferManager.cleanupAll();
        
        // Clean up shaders and program
        resourceManager.cleanupAll();
        
        // Clear uniforms
        uniformManager.clear();
        
        LOGGER.log(Level.FINE, "Cleaned up shader program (ID: {0})", programId);
    }
}