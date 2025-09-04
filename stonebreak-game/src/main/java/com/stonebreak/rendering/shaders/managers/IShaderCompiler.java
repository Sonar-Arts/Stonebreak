package com.stonebreak.rendering.shaders.managers;

import com.stonebreak.rendering.shaders.exceptions.ShaderCompilationException;
import com.stonebreak.rendering.shaders.exceptions.ShaderLinkException;

/**
 * Interface for compiling OpenGL shaders.
 * Follows Single Responsibility Principle - only handles shader compilation.
 */
public interface IShaderCompiler {
    
    /**
     * Compiles a shader from source code.
     * @param shaderCode The source code of the shader
     * @param shaderType The OpenGL shader type (GL_VERTEX_SHADER, GL_FRAGMENT_SHADER, etc.)
     * @return The compiled shader ID
     * @throws ShaderCompilationException if compilation fails
     */
    int compileShader(String shaderCode, int shaderType) throws ShaderCompilationException;
    
    /**
     * Links a shader program with the provided shader IDs.
     * @param programId The program ID to link
     * @param shaderIds Array of shader IDs to link
     * @throws ShaderLinkException if linking fails
     */
    void linkProgram(int programId, int... shaderIds) throws ShaderLinkException;
    
    /**
     * Validates a shader program.
     * @param programId The program ID to validate
     * @return true if validation passes, false otherwise
     */
    boolean validateProgram(int programId);
    
    /**
     * Gets the info log for a shader program.
     * @param programId The program ID
     * @return The info log string
     */
    String getProgramInfoLog(int programId);
}