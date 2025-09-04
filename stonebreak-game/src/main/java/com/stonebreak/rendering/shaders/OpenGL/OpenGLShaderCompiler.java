package com.stonebreak.rendering.shaders.OpenGL;

import com.stonebreak.rendering.shaders.managers.IShaderCompiler;
import com.stonebreak.rendering.shaders.exceptions.ShaderCompilationException;
import com.stonebreak.rendering.shaders.exceptions.ShaderLinkException;

import java.util.logging.Logger;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL32.GL_GEOMETRY_SHADER;

/**
 * OpenGL implementation of shader compiler.
 * Handles shader compilation, linking, and validation with proper error handling.
 */
public class OpenGLShaderCompiler implements IShaderCompiler {
    
    private static final Logger LOGGER = Logger.getLogger(OpenGLShaderCompiler.class.getName());
    private final boolean debugMode;
    
    public OpenGLShaderCompiler() {
        this(false);
    }
    
    public OpenGLShaderCompiler(boolean debugMode) {
        this.debugMode = debugMode;
    }
    
    @Override
    public int compileShader(String shaderCode, int shaderType) throws ShaderCompilationException {
        if (shaderCode == null || shaderCode.trim().isEmpty()) {
            throw new ShaderCompilationException("Shader source code cannot be null or empty", shaderType, shaderCode);
        }
        
        int shaderId = glCreateShader(shaderType);
        if (shaderId == 0) {
            throw new ShaderCompilationException("Failed to create shader object", shaderType, shaderCode);
        }
        
        if (debugMode) {
            logShaderInfo(shaderType, shaderCode);
        }
        
        try {
            glShaderSource(shaderId, shaderCode);
            glCompileShader(shaderId);
            
            if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
                String errorLog = glGetShaderInfoLog(shaderId, 1024);
                glDeleteShader(shaderId); // Clean up on failure
                throw new ShaderCompilationException(
                    "Shader compilation failed: " + errorLog, 
                    shaderType, 
                    shaderCode
                );
            }
            
            LOGGER.log(Level.FINE, "Successfully compiled {0} shader (ID: {1})", 
                      new Object[]{getShaderTypeName(shaderType), shaderId});
            
            return shaderId;
            
        } catch (Exception e) {
            // Ensure cleanup on any exception
            if (shaderId != 0) {
                glDeleteShader(shaderId);
            }
            if (e instanceof ShaderCompilationException) {
                throw e;
            }
            throw new ShaderCompilationException("Unexpected error during shader compilation", shaderType, shaderCode, e);
        }
    }
    
    @Override
    public void linkProgram(int programId, int... shaderIds) throws ShaderLinkException {
        if (programId == 0) {
            throw new ShaderLinkException("Invalid program ID: 0", programId);
        }
        
        if (shaderIds == null || shaderIds.length == 0) {
            throw new ShaderLinkException("No shaders provided for linking", programId);
        }
        
        // Attach all shaders
        for (int shaderId : shaderIds) {
            if (shaderId != 0) {
                glAttachShader(programId, shaderId);
            }
        }
        
        // Link the program
        glLinkProgram(programId);
        
        if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
            String errorLog = glGetProgramInfoLog(programId, 1024);
            throw new ShaderLinkException("Program linking failed: " + errorLog, programId);
        }
        
        // Detach shaders after successful linking
        for (int shaderId : shaderIds) {
            if (shaderId != 0) {
                glDetachShader(programId, shaderId);
            }
        }
        
        LOGGER.log(Level.FINE, "Successfully linked shader program (ID: {0})", programId);
    }
    
    @Override
    public boolean validateProgram(int programId) {
        if (programId == 0) {
            return false;
        }
        
        glValidateProgram(programId);
        boolean isValid = glGetProgrami(programId, GL_VALIDATE_STATUS) != 0;
        
        if (!isValid) {
            String validationLog = glGetProgramInfoLog(programId, 1024);
            LOGGER.log(Level.WARNING, "Shader program validation failed (ID: {0}): {1}", 
                      new Object[]{programId, validationLog});
        }
        
        return isValid;
    }
    
    @Override
    public String getProgramInfoLog(int programId) {
        if (programId == 0) {
            return "Invalid program ID: 0";
        }
        return glGetProgramInfoLog(programId, 1024);
    }
    
    private void logShaderInfo(int shaderType, String shaderCode) {
        String shaderTypeName = getShaderTypeName(shaderType);
        LOGGER.log(Level.INFO, "---- Compiling {0} Shader ----", shaderTypeName);
        LOGGER.log(Level.INFO, "Shader Code Length: {0}", shaderCode.length());
        LOGGER.log(Level.FINE, "Shader Code:\n{0}", shaderCode);
        LOGGER.log(Level.INFO, "---- End {0} Shader ----", shaderTypeName);
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
}