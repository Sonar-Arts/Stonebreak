package com.stonebreak.rendering.shaders.exceptions;

/**
 * Exception thrown when shader compilation fails.
 */
public class ShaderCompilationException extends Exception {
    
    private final int shaderType;
    private final String shaderSource;
    
    public ShaderCompilationException(String message, int shaderType, String shaderSource) {
        super(message);
        this.shaderType = shaderType;
        this.shaderSource = shaderSource;
    }
    
    public ShaderCompilationException(String message, int shaderType, String shaderSource, Throwable cause) {
        super(message, cause);
        this.shaderType = shaderType;
        this.shaderSource = shaderSource;
    }
    
    public int getShaderType() {
        return shaderType;
    }
    
    public String getShaderSource() {
        return shaderSource;
    }
    
    @Override
    public String toString() {
        return String.format("ShaderCompilationException[type=%d]: %s", shaderType, getMessage());
    }
}